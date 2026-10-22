package com.uyirhealthapp.payment_service.refund;

import com.uyirhealthapp.payment_service.common.ApiException;
import com.uyirhealthapp.payment_service.common.PaymentMetrics;
import com.uyirhealthapp.payment_service.gateway.GatewayException;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.Outcome;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.RefundRequest;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.RefundResult;
import com.uyirhealthapp.payment_service.payment.Payment;
import com.uyirhealthapp.payment_service.payment.PaymentDtos;
import com.uyirhealthapp.payment_service.payment.PaymentDtos.RefundView;
import com.uyirhealthapp.payment_service.payment.PaymentRepository;
import com.uyirhealthapp.payment_service.payment.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Refunds of PAID payments (admin only). Two steps so concurrent refunds cannot exceed the paid amount:
 * 1) under the payment's row lock, reserve the amount as a PENDING refund; 2) call the provider outside the transaction
 * and record its answer. Razorpay often answers "pending"; the refund.* webhook settles it later.
 */
@Service
@Slf4j
public class RefundService {

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    private final PaymentMetrics metrics;

    public RefundService(PaymentRepository payments, RefundRepository refunds, PaymentGateway gateway,
                         TransactionTemplate tx, PaymentMetrics metrics) {
        this.payments = payments;
        this.refunds = refunds;
        this.gateway = gateway;
        this.tx = tx;
        this.metrics = metrics;
    }

    public RefundView refund(UUID paymentId, PaymentDtos.RefundRequest req, String adminId) {
        Refund reserved = tx.execute(s -> reserve(paymentId, req, adminId));
        Payment p = payments.findById(paymentId).orElseThrow();
        RefundResult result;
        try {
            result = gateway.refund(new RefundRequest(reserved.getId(), p.getProviderPaymentId(),
                    reserved.getAmount(), reserved.getReason().code(), reserved.getNote()));
        } catch (GatewayException e) {
            log.error("Refund {} of payment {} failed at the provider: {}", reserved.getId(), paymentId, e.getMessage());
            if (e.isRetryable()) {
                // we cannot know whether the provider created it: keep it PENDING (reserved); RefundReconciler
                // finds out, and marks it FAILED (amount free again) if the provider never created it
                throw ApiException.serviceUnavailable("Payment provider unavailable. Refund " + reserved.getId()
                        + " is PENDING and is checked with the payment provider automatically; do not retry it");
            }
            tx.executeWithoutResult(s -> apply(reserved.getId(), null, Outcome.FAILED, e.getMessage()));
            throw ApiException.badGateway("Payment provider rejected the refund");
        }
        // self-invocation bypasses @Transactional on apply(), hence the explicit transaction
        return RefundView.of(tx.execute(s -> apply(reserved.getId(), result.providerRefundId(), result.outcome(),
                result.failureReason())));
    }

    private Refund reserve(UUID paymentId, PaymentDtos.RefundRequest req, String adminId) {
        Payment p = payments.lockById(paymentId).orElseThrow(() -> ApiException.notFound("Payment not found"));
        if (p.getStatus() != PaymentStatus.PAID) {
            throw ApiException.conflict("Only a PAID payment can be refunded");
        }
        if (!gateway.name().equals(p.getProvider())) {
            // paid through the provider used before a switch: its payment id means nothing to the current one
            throw ApiException.conflict("Payment was made through " + p.getProvider() + "; refund it in the "
                    + p.getProvider() + " dashboard");
        }
        BigDecimal taken = refunds.sumAmount(paymentId, EnumSet.of(Refund.Status.PENDING, Refund.Status.SUCCEEDED));
        BigDecimal refundable = p.getAmount().subtract(taken);
        BigDecimal amount = req.amount() == null ? refundable : req.amount().setScale(2);
        if (amount.signum() <= 0 || amount.compareTo(refundable) > 0) {
            throw ApiException.conflict("At most " + refundable + " " + p.getCurrency() + " can be refunded");
        }
        Refund r = new Refund();
        r.setId(UUID.randomUUID());
        r.setPaymentId(paymentId);
        r.setAmount(amount);
        r.setReason(req.reason());
        r.setNote(req.note());
        r.setStatus(Refund.Status.PENDING);
        r.setRequestedBy(adminId);
        log.info("Refund {} of {} {} for payment {} requested by {}", r.getId(), amount, p.getCurrency(), paymentId,
                adminId);
        return refunds.save(r);
    }

    /** Records the provider's answer (synchronous or via the refund.* webhook). Idempotent; final states stay final. */
    @Transactional
    public Refund apply(UUID refundId, String providerRefundId, Outcome outcome, String failureReason) {
        Refund r = refunds.findById(refundId).orElseThrow();
        if (providerRefundId != null && r.getProviderRefundId() == null) {
            r.setProviderRefundId(providerRefundId);
        }
        if (r.getStatus() != Refund.Status.PENDING || outcome == Outcome.OPEN) {
            return r;
        }
        Payment p = payments.lockById(r.getPaymentId()).orElseThrow();
        if (outcome == Outcome.SUCCEEDED) {
            r.setStatus(Refund.Status.SUCCEEDED);
            p.setRefundedAmount(p.getRefundedAmount().add(r.getAmount()));
            log.info("Refund {} of {} {} for payment {} SUCCEEDED", r.getId(), r.getAmount(), p.getCurrency(), p.getId());
        } else {
            r.setStatus(Refund.Status.FAILED);
            r.setFailureReason(failureReason == null ? "refund_failed"
                    : failureReason.substring(0, Math.min(255, failureReason.length())));
            log.warn("Refund {} for payment {} FAILED: {}", r.getId(), p.getId(), r.getFailureReason());
        }
        metrics.refunded(r.getStatus().name());
        return r;
    }

    /** refund.processed / refund.failed webhook. refundRef = our refund id from the notes, if echoed. */
    @Transactional
    public boolean onProviderUpdate(String providerRefundId, String refundRef, Outcome outcome, String failureReason) {
        Refund r = refunds.findByProviderRefundId(providerRefundId).orElse(null);
        if (r == null && refundRef != null) {
            try {
                r = refunds.findById(UUID.fromString(refundRef)).orElse(null);
            } catch (IllegalArgumentException ignored) {
                // not one of ours
            }
        }
        if (r == null) {
            return false;
        }
        apply(r.getId(), providerRefundId, outcome, failureReason);
        return true;
    }
}
