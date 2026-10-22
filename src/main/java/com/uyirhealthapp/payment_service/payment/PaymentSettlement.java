package com.uyirhealthapp.payment_service.payment;

import com.uyirhealthapp.payment_service.common.PaymentMetrics;
import com.uyirhealthapp.payment_service.notify.ConsumerNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

/**
 * The only place a payment's status changes after creation. Called from the Razorpay webhook, from reconciliation
 * and from the mock provider; every method is idempotent, because Razorpay retries webhooks and reconciliation may
 * report the same result again. Each change that a module cares about is queued for it in the same transaction.
 */
@Service
@Slf4j
public class PaymentSettlement {

    private final PaymentRepository payments;
    private final CheckoutSessionRepository sessions;
    private final ConsumerNotifier notifier;
    private final PaymentMetrics metrics;
    private final Clock clock;

    public PaymentSettlement(PaymentRepository payments, CheckoutSessionRepository sessions,
                             ConsumerNotifier notifier, PaymentMetrics metrics, Clock clock) {
        this.payments = payments;
        this.sessions = sessions;
        this.notifier = notifier;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * The provider says money arrived. amount/currency may be null when the provider did not report them
     * (the check is then skipped).
     */
    @Transactional
    public void paid(UUID paymentId, String providerSessionId, String providerPaymentId, BigDecimal amount,
                     String currency) {
        Payment p = payments.lockById(paymentId).orElse(null);
        if (p == null) {
            log.error("Provider reported payment {} ({}) for unknown payment {}", providerPaymentId,
                    providerSessionId, paymentId);
            metrics.anomaly("unknown_payment");
            return;
        }
        closeSession(providerSessionId, CheckoutSession.Status.SUCCEEDED);
        if (p.getStatus() == PaymentStatus.PAID) {
            if (providerPaymentId != null && !providerPaymentId.equals(p.getProviderPaymentId())) {
                // two checkouts of the same payment both went through: the user paid twice
                log.error("DOUBLE PAYMENT: payment {} ({} {}) is already paid by {}, and {} was paid too. "
                                + "Refund one via POST /api/payments/{}/refunds after checking with the payment provider.",
                        p.getId(), p.getReferenceType(), p.getReferenceId(), p.getProviderPaymentId(),
                        providerPaymentId, p.getId());
                metrics.anomaly("double_payment");
            }
            return;
        }
        if (amount != null && amount.compareTo(p.getAmount()) < 0
                || currency != null && !currency.equalsIgnoreCase(p.getCurrency())) {
            log.error("Payment {}: provider reports {} {} but {} {} is owed; NOT marking it paid",
                    p.getId(), amount, currency, p.getAmount(), p.getCurrency());
            metrics.anomaly("amount_mismatch");
            return;
        }
        p.setStatus(PaymentStatus.PAID);
        p.setProviderPaymentId(providerPaymentId);
        p.setPaidAt(clock.instant());
        p.setFailureReason(null);
        notifier.paymentChanged(p);
        metrics.paid(p.getReferenceType());
        log.info("Payment {} PAID ({} {} for {} {}, provider payment {})", p.getId(), p.getAmount(), p.getCurrency(),
                p.getReferenceType(), p.getReferenceId(), providerPaymentId);
    }

    /** The provider says an attempt failed. The user can start a new checkout; a PAID payment stays PAID. */
    @Transactional
    public void failed(UUID paymentId, String providerSessionId, String reason) {
        Payment p = payments.lockById(paymentId).orElse(null);
        if (p == null) {
            log.warn("Provider reported a failure for unknown payment {} ({})", paymentId, providerSessionId);
            return;
        }
        closeSession(providerSessionId, CheckoutSession.Status.FAILED);
        if (p.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        // another checkout of the same payment may still be open (the user retried); only that one counts now
        boolean anotherOpen = sessions.findByPaymentIdAndStatus(p.getId(), CheckoutSession.Status.OPEN).stream()
                .anyMatch(s -> !s.getProviderSessionId().equals(providerSessionId));
        if (anotherOpen) {
            return;
        }
        p.setStatus(PaymentStatus.FAILED);
        p.setFailureReason(reason == null ? "payment_failed" : truncate(reason));
        notifier.paymentChanged(p);
        metrics.failed(p.getReferenceType());
        log.info("Payment {} FAILED ({}): {}", p.getId(), p.getReferenceType(), p.getFailureReason());
    }

    /** A checkout ran out of time unused. Nothing to tell the module: the payment is still owed. */
    @Transactional
    public void expired(String providerSessionId) {
        closeSession(providerSessionId, CheckoutSession.Status.EXPIRED);
    }

    private void closeSession(String providerSessionId, CheckoutSession.Status status) {
        if (providerSessionId == null) {
            return;
        }
        sessions.findByProviderSessionId(providerSessionId).ifPresent(s -> {
            // SUCCEEDED is final; anything else may still turn into SUCCEEDED (late webhook)
            if (s.getStatus() != CheckoutSession.Status.SUCCEEDED) {
                s.setStatus(status);
            }
        });
    }

    private static String truncate(String s) {
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
