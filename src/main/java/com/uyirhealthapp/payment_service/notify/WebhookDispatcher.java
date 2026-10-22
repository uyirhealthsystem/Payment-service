package com.uyirhealthapp.payment_service.notify;

import com.uyirhealthapp.payment_service.common.Hmac;
import com.uyirhealthapp.payment_service.common.PaymentMetrics;
import com.uyirhealthapp.payment_service.config.PaymentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Delivers queued result callbacks to the modules. Each round claims a batch (locked rows, lease pushed forward so
 * a crashed instance's claim is picked up again later), POSTs outside any transaction, then records the outcome.
 * The signature is computed at send time, so the module's timestamp tolerance applies to the attempt, not to
 * when the event happened.
 */
@Component
@Slf4j
public class WebhookDispatcher {

    /** A claimed delivery is not picked up by another instance for this long. */
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final PaymentProperties props;
    private final WebhookDeliveryRepository deliveries;
    private final TransactionTemplate tx;
    private final PaymentMetrics metrics;
    private final Clock clock;
    private final RestClient http;

    public WebhookDispatcher(PaymentProperties props, WebhookDeliveryRepository deliveries, TransactionTemplate tx,
                             PaymentMetrics metrics, Clock clock) {
        this.props = props;
        this.deliveries = deliveries;
        this.tx = tx;
        this.metrics = metrics;
        this.clock = clock;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.delivery().connectTimeout());
        factory.setReadTimeout(props.delivery().readTimeout());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${payment.delivery.poll-interval:3s}")
    public void dispatchDue() {
        List<WebhookDelivery> batch = claim();
        for (WebhookDelivery d : batch) {
            deliver(d);
        }
    }

    List<WebhookDelivery> claim() {
        return tx.execute(s -> {
            Instant now = clock.instant();
            List<WebhookDelivery> due = deliveries.lockDue(now, props.delivery().batchSize());
            due.forEach(d -> d.setNextAttemptAt(now.plus(LEASE)));
            return due;
        });
    }

    void deliver(WebhookDelivery d) {
        PaymentProperties.Consumer consumer = props.consumer(d.getReferenceType());
        if (consumer == null || !consumer.notifies()) {
            finish(d.getId(), false, "consumer " + d.getReferenceType() + " is no longer configured", true);
            return;
        }
        byte[] body = d.getPayload().getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        String error;
        try {
            http.post().uri(d.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Payment-Timestamp", timestamp)
                    .header("X-Payment-Signature", Hmac.sign(consumer.webhookSecret(), timestamp, body))
                    .body(body)
                    .retrieve().toBodilessEntity();
            finish(d.getId(), true, null, false);
            return;
        } catch (RestClientResponseException e) {
            error = "HTTP " + e.getStatusCode().value() + " " + abbreviate(e.getResponseBodyAsString());
        } catch (RestClientException e) {
            error = e.getMessage();
        }
        finish(d.getId(), false, error, false);
    }

    private void finish(long id, boolean delivered, String error, boolean giveUp) {
        tx.executeWithoutResult(s -> deliveries.findById(id).ifPresent(d -> {
            Instant now = clock.instant();
            d.setAttempts(d.getAttempts() + 1);
            if (delivered) {
                d.setStatus(WebhookDelivery.Status.DELIVERED);
                d.setDeliveredAt(now);
                d.setLastError(null);
                metrics.callback(d.getReferenceType(), "delivered");
                log.info("Delivered payment {} callback to {} (attempt {})", d.getPaymentId(), d.getReferenceType(),
                        d.getAttempts());
                return;
            }
            d.setLastError(abbreviate(error));
            if (giveUp || d.getAttempts() >= props.delivery().maxAttempts()) {
                d.setStatus(WebhookDelivery.Status.DEAD);
                metrics.callback(d.getReferenceType(), "dead");
                log.error("Giving up on payment {} callback to {} after {} attempts: {}. The module never learnt "
                        + "the result; it can still read GET /api/payments/{}", d.getPaymentId(), d.getReferenceType(),
                        d.getAttempts(), error, d.getPaymentId());
                return;
            }
            d.setNextAttemptAt(now.plus(backoff(d.getAttempts())));
            metrics.callback(d.getReferenceType(), "retry");
            log.warn("Payment {} callback to {} failed (attempt {}), retrying at {}: {}", d.getPaymentId(),
                    d.getReferenceType(), d.getAttempts(), d.getNextAttemptAt(), error);
        }));
    }

    /** initialBackoff * 2^(attempts-1), capped at maxBackoff: 10s, 20s, 40s, ... 1h. */
    Duration backoff(int attempts) {
        Duration initial = props.delivery().initialBackoff();
        Duration max = props.delivery().maxBackoff();
        int shift = Math.min(attempts - 1, 30);
        Duration next = initial.multipliedBy(1L << shift);
        return next.compareTo(max) > 0 ? max : next;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 480 ? s : s.substring(0, 480) + "...";
    }
}
