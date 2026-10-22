package com.uyirhealthapp.payment_service.payment;

import com.uyirhealthapp.payment_service.config.PaymentProperties;
import com.uyirhealthapp.payment_service.gateway.GatewayException;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.SessionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Safety net for lost webhooks: asks the provider about checkouts that have been open for a while and applies what it
 * says. Also used on demand (POST /api/payments/{id}/refresh) when the user is back from the checkout page.
 */
@Component
@Slf4j
public class CheckoutReconciler {

    private final PaymentProperties props;
    private final CheckoutSessionRepository sessions;
    private final PaymentGateway gateway;
    private final PaymentSettlement settlement;
    private final Clock clock;

    public CheckoutReconciler(PaymentProperties props, CheckoutSessionRepository sessions, PaymentGateway gateway,
                              PaymentSettlement settlement, Clock clock) {
        this.props = props;
        this.sessions = sessions;
        this.gateway = gateway;
        this.settlement = settlement;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payment.reconcile.interval:60s}", initialDelayString = "30s")
    public void reconcileStale() {
        PaymentProperties.Reconcile cfg = props.reconcile();
        if (!cfg.enabled()) {
            return;
        }
        Instant now = clock.instant();
        List<CheckoutSession> stale = sessions.findByStatusAndCreatedAtBeforeOrderByCreatedAt(
                CheckoutSession.Status.OPEN, now.minus(cfg.after()), Limit.of(cfg.batchSize()));
        for (CheckoutSession s : stale) {
            if (s.getCreatedAt().isBefore(now.minus(cfg.giveUpAfter()))) {
                settlement.expired(s.getProviderSessionId());
                continue;
            }
            try {
                check(s);
            } catch (GatewayException e) {
                if (e.isRetryable()) {
                    log.warn("Reconciliation paused, payment provider unavailable: {}", e.getMessage());
                    return;
                }
                // this one checkout is unknown to the provider (e.g. made by the previous provider): skip it, it
                // expires after give-up-after
                log.warn("Checkout {} could not be looked up: {}", s.getProviderSessionId(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Reconciliation of checkout {} failed", s.getProviderSessionId(), e);
            }
        }
    }

    /** Asks the provider about one checkout and applies the answer. */
    public void check(CheckoutSession s) {
        SessionState state = gateway.getSession(s.getProviderSessionId());
        switch (state.outcome()) {
            case SUCCEEDED -> settlement.paid(s.getPaymentId(), s.getProviderSessionId(), state.providerPaymentId(),
                    state.amount(), state.currency());
            case FAILED -> settlement.failed(s.getPaymentId(), s.getProviderSessionId(), "payment_failed");
            case EXPIRED -> settlement.expired(s.getProviderSessionId());
            case OPEN -> {
                // still in progress on the checkout page
            }
        }
    }
}
