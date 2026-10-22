package com.uyirhealthapp.payment_service.linkedaccount;

import com.uyirhealthapp.payment_service.config.PaymentProperties;
import com.uyirhealthapp.payment_service.gateway.GatewayException;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.Activation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumSet;

/** Safety net for lost Razorpay product.route.* webhooks: looks up accounts that are still waiting on review. */
@Component
@Slf4j
public class LinkedAccountReconciler {

    private static final EnumSet<Activation> WAITING =
            EnumSet.of(Activation.PENDING_DETAILS, Activation.UNDER_REVIEW, Activation.NEEDS_CLARIFICATION);

    private final LinkedAccountRepository accounts;
    private final LinkedAccountService service;
    private final LinkedAccountProperties props;
    private final PaymentProperties paymentProps;
    private final Clock clock;

    public LinkedAccountReconciler(LinkedAccountRepository accounts, LinkedAccountService service,
                                   LinkedAccountProperties props, PaymentProperties paymentProps, Clock clock) {
        this.accounts = accounts;
        this.service = service;
        this.props = props;
        this.paymentProps = paymentProps;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${payment.linked-accounts.refresh-interval:10m}", initialDelayString = "60s")
    public void refreshWaiting() {
        if (!paymentProps.reconcile().enabled()) {
            return;
        }
        var waiting = accounts.findByStatusInAndProviderProductIdIsNotNullAndUpdatedAtBeforeOrderByUpdatedAt(
                WAITING, clock.instant().minus(props.refreshAfter()), Limit.of(paymentProps.reconcile().batchSize()));
        for (LinkedAccount a : waiting) {
            try {
                service.refresh(a);
            } catch (GatewayException e) {
                if (e.isRetryable()) {
                    log.warn("Linked account refresh paused, payment provider unavailable: {}", e.getMessage());
                    return;
                }
                log.warn("Linked account {} could not be looked up: {}", a.getId(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Refresh of linked account {} failed", a.getId(), e);
            }
        }
    }
}
