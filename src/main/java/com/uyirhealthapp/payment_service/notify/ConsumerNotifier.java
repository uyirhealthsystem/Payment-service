package com.uyirhealthapp.payment_service.notify;

import com.uyirhealthapp.payment_service.config.PaymentProperties;
import com.uyirhealthapp.payment_service.payment.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Queues "payment X is now PAID / FAILED" for the module that owns it. Joins the caller's transaction, so the
 * callback exists if and only if the status change was committed (transactional outbox).
 */
@Component
@Slf4j
public class ConsumerNotifier {

    private final PaymentProperties props;
    private final WebhookDeliveryRepository deliveries;
    private final JsonMapper json;
    private final Clock clock;

    public ConsumerNotifier(PaymentProperties props, WebhookDeliveryRepository deliveries, JsonMapper json,
                            Clock clock) {
        this.props = props;
        this.deliveries = deliveries;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentChanged(Payment p) {
        PaymentProperties.Consumer consumer = props.consumer(p.getReferenceType());
        if (consumer == null || !consumer.notifies()) {
            log.debug("No webhook configured for {}, not notifying about payment {}", p.getReferenceType(), p.getId());
            return;
        }
        Instant now = clock.instant();
        PaymentEvent event = new PaymentEvent(UUID.randomUUID(), p.getId(),p.getReferenceType(), p.getReferenceId(),
                p.getReferenceId(), p.getPayerId(), p.getStatus(), p.getAmount(), p.getCurrency(),
                p.getRefundedAmount(), p.getFailureReason(), now);
        WebhookDelivery d = new WebhookDelivery();
        d.setPaymentId(p.getId());
        d.setReferenceType(p.getReferenceType());
        d.setUrl(consumer.webhookUrl());
        d.setPayload(json.writeValueAsString(event));
        d.setStatus(WebhookDelivery.Status.PENDING);
        d.setNextAttemptAt(now);
        d.setCreatedAt(now);
        deliveries.save(d);
    }
}
