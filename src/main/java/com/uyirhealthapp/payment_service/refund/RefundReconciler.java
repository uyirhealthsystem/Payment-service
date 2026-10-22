package com.uyirhealthapp.payment_service.refund;

import com.uyirhealthapp.payment_service.config.PaymentProperties;
import com.uyirhealthapp.payment_service.gateway.GatewayException;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.Outcome;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.RefundResult;
import com.uyirhealthapp.payment_service.payment.Payment;
import com.uyirhealthapp.payment_service.payment.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Safety net for refunds stuck PENDING: the refund call timed out (did the provider create it?) or its refund.*
 * webhook went missing. Asks the provider and applies the answer; a refund the provider still does not know long
 * after it was requested was never created there, so it is marked FAILED and its amount can be refunded again.
 */
@Component
@Slf4j
public class RefundReconciler {

    private final PaymentProperties props;
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final RefundService refundService;
    private final PaymentGateway gateway;
    private final Clock clock;

    public RefundReconciler(PaymentProperties props, RefundRepository refunds, PaymentRepository payments,
                            RefundService refundService, PaymentGateway gateway, Clock clock) {
        this.props = props;
        this.refunds = refunds;
        this.payments = payments;
        this.refundService = refundService;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payment.reconcile.interval:60s}", initialDelayString = "45s")
    public void reconcilePending() {
        PaymentProperties.Reconcile cfg = props.reconcile();
        if (!cfg.enabled()) {
            return;
        }
        Instant now = clock.instant();
        List<Refund> pending = refunds.findByStatusAndUpdatedAtBeforeOrderByUpdatedAt(Refund.Status.PENDING,
                now.minus(cfg.refundAfter()), Limit.of(cfg.batchSize()));
        for (Refund r : pending) {
            try {
                check(r, now);
            } catch (GatewayException e) {
                if (e.isRetryable()) {
                    log.warn("Refund reconciliation paused, payment provider unavailable: {}", e.getMessage());
                    return;
                }
                log.warn("Refund {} could not be looked up: {}", r.getId(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Reconciliation of refund {} failed", r.getId(), e);
            }
            refunds.touch(r.getId(), now); // still PENDING (or failed to check): next in line after the others
        }
    }

    private void check(Refund r, Instant now) {
        Payment p = payments.findById(r.getPaymentId()).orElseThrow();
        RefundResult found = gateway.findRefund(r.getId(), p.getProviderPaymentId(), r.getProviderRefundId());
        if (found != null) {
            refundService.apply(r.getId(), found.providerRefundId(), found.outcome(), found.failureReason());
            return;
        }
        if (r.getCreatedAt().isBefore(now.minus(props.reconcile().refundNotFoundAfter()))) {
            log.warn("Refund {} of payment {} was never created at the provider: FAILED", r.getId(), p.getId());
            refundService.apply(r.getId(), null, Outcome.FAILED, "not_created_at_provider");
        }
    }
}
