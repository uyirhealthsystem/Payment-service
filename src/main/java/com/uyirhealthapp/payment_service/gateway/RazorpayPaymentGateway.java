package com.uyirhealthapp.payment_service.gateway;

import com.uyirhealthapp.payment_service.config.PaymentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Razorpay, through Payment Links (https://razorpay.com/docs/api/payments/payment-links/): a hosted page per
 * checkout, so modules and apps only ever see a checkoutUrl. Calls use Basic auth with the API key.
 *
 * <pre>
 *   POST /payment_links                  create a checkout -> id (plink_...), short_url
 *   GET  /payment_links/{id}             status of a checkout (reconciliation): payments[] of captured payments
 *   POST /payment_links/{id}/cancel      cancel a checkout that was replaced by a newer one
 *   POST /payments/{pay_id}/refund       refund
 *   GET  /refunds/{id}                   status of a refund (reconciliation)
 *   GET  /payments/{pay_id}/refunds      a payment's refunds: finds ours by notes.refund_id when its id is unknown
 * </pre>
 *
 * Amounts go to Razorpay in paise. Our payment id goes into notes.payment_id, so every webhook can be matched
 * back to it even before the link id is stored. A failed attempt does not end a link: the user can try again on
 * the same page until it is paid or expires, so Razorpay never reports a checkout as FAILED.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "razorpay", matchIfMissing = true)
@Slf4j
public class RazorpayPaymentGateway implements PaymentGateway {

    private final PaymentProperties.Razorpay cfg;
    private final RestClient http;
    private final Clock clock;

    @Autowired
    public RazorpayPaymentGateway(PaymentProperties props, Clock clock) {
        this(props, clock, RestClient.builder().requestFactory(requestFactory(props.razorpay())));
    }

    /** For tests: lets a mock server be bound to the builder. */
    RazorpayPaymentGateway(PaymentProperties props, Clock clock, RestClient.Builder builder) {
        this.cfg = props.razorpay();
        this.clock = clock;
        String basic = Base64.getEncoder()
                .encodeToString((cfg.keyId() + ":" + cfg.keySecret()).getBytes(StandardCharsets.UTF_8));
        this.http = builder.baseUrl(cfg.apiBaseUrl()).defaultHeader("Authorization", "Basic " + basic).build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(PaymentProperties.Razorpay cfg) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(cfg.connectTimeout());
        factory.setReadTimeout(cfg.readTimeout());
        return factory;
    }

    @Override
    public String name() {
        return "razorpay";
    }

    @Override
    public Session createSession(SessionRequest r) {
        Instant expiresAt = clock.instant().plus(cfg.linkExpiry());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", toPaise(r.amount()));
        body.put("currency", r.currency());
        body.put("description", truncate(r.description(), 2048));
        body.put("expire_by", expiresAt.getEpochSecond());
        // reference_id must be unique per link, and a payment can need several links (retry after expiry): our
        // payment id goes into notes instead
        body.put("notes", Map.of(
                "payment_id", r.paymentId().toString(),
                "reference_type", truncate(r.referenceType(), 256),
                "reference_id", truncate(r.referenceId(), 256)));
        // the apps show the link themselves; no SMS / e-mail / reminders from Razorpay
        body.put("notify", Map.of("sms", false, "email", false));
        body.put("reminder_enable", false);
        // one return page: Razorpay keeps the user on its page after a failed attempt
        body.put("callback_url", r.successUrl());
        body.put("callback_method", "get");

        JsonNode link = call(() -> http.post().uri("/payment_links").body(body).retrieve().body(JsonNode.class),
                "create payment link");
        String id = text(link, "id");
        String url = text(link, "short_url");
        if (id == null || url == null) {
            throw new GatewayException("Razorpay returned a payment link without id / short_url", false);
        }
        long expireBy = link.path("expire_by").asLong(0);
        return new Session(id, url, expireBy > 0 ? Instant.ofEpochSecond(expireBy) : expiresAt, null);
    }

    @Override
    public SessionState getSession(String sessionId) {
        JsonNode link = call(() -> http.get().uri("/payment_links/{id}", sessionId).retrieve().body(JsonNode.class),
                "get payment link");
        String currency = text(link, "currency");
        BigDecimal paid = fromPaise(link.path("amount_paid"));
        for (JsonNode p : link.path("payments")) { // only captured payments are listed; null until the first
            if ("captured".equalsIgnoreCase(text(p, "status"))) {
                return new SessionState(Outcome.SUCCEEDED, text(p, "payment_id"), paid, currency);
            }
        }
        long expireBy = link.path("expire_by").asLong(0);
        boolean expired = expireBy > 0 && clock.instant().isAfter(Instant.ofEpochSecond(expireBy));
        Outcome outcome = switch (String.valueOf(text(link, "status")).toLowerCase()) {
            case "expired", "cancelled" -> Outcome.EXPIRED;
            // "paid" without a captured payment listed should not happen; ask again on the next round
            default -> expired ? Outcome.EXPIRED : Outcome.OPEN;
        };
        return new SessionState(outcome, null, paid, currency);
    }

    @Override
    public void cancelSession(String sessionId) {
        call(() -> http.post().uri("/payment_links/{id}/cancel", sessionId).retrieve().body(JsonNode.class),
                "cancel payment link");
    }

    @Override
    public RefundResult refund(RefundRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", toPaise(r.amount()));
        body.put("speed", "normal");
        body.put("receipt", r.refundId().toString());
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("refund_id", r.refundId().toString());
        notes.put("reason", r.reasonCode());
        if (r.note() != null) {
            notes.put("note", truncate(r.note(), 256));
        }
        body.put("notes", notes);

        // X-Refund-Idempotency (https://razorpay.com/docs/api/refunds/normal-refunds-idempotent/): a retry with the
        // same key and the same body returns the refund already created instead of paying it out twice. The body is
        // built only from the stored refund, so it is identical on every retry.
        JsonNode refund = call(() -> http.post().uri("/payments/{id}/refund", r.providerPaymentId())
                .header("X-Refund-Idempotency", r.refundId().toString()).body(body)
                .retrieve().body(JsonNode.class), "create refund");
        String id = text(refund, "id");
        if (id == null) {
            throw new GatewayException("Razorpay returned a refund without id", false);
        }
        return new RefundResult(id, refundOutcome(text(refund, "status")), null);
    }

    @Override
    public RefundResult findRefund(UUID refundId, String providerPaymentId, String providerRefundId) {
        if (providerRefundId != null) {
            JsonNode refund = call(() -> http.get().uri("/refunds/{id}", providerRefundId).retrieve()
                    .body(JsonNode.class), "get refund");
            return refundResult(refund);
        }
        JsonNode list = call(() -> http.get().uri("/payments/{id}/refunds?count=100", providerPaymentId).retrieve()
                .body(JsonNode.class), "list refunds");
        String ours = refundId.toString();
        for (JsonNode refund : list.path("items")) {
            if (ours.equals(text(refund.path("notes"), "refund_id")) || ours.equals(text(refund, "receipt"))) {
                return refundResult(refund);
            }
        }
        return null;
    }

    private static RefundResult refundResult(JsonNode refund) {
        Outcome outcome = refundOutcome(text(refund, "status"));
        return new RefundResult(text(refund, "id"), outcome,
                outcome == Outcome.FAILED ? "refund_failed_at_razorpay" : null);
    }

    /** Razorpay refund status -> ours. "pending" settles later via the refund.processed / refund.failed webhooks. */
    public static Outcome refundOutcome(String status) {
        return switch (String.valueOf(status).toLowerCase()) {
            case "processed" -> Outcome.SUCCEEDED;
            case "failed" -> Outcome.FAILED;
            default -> Outcome.OPEN;
        };
    }

    /** 640.00 -> 64000. Amounts are stored with two decimals, so this is exact. */
    public static long toPaise(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    /** 64000 -> 640.00; null when Razorpay did not send a number. */
    public static BigDecimal fromPaise(JsonNode paise) {
        return paise.isNumber() ? BigDecimal.valueOf(paise.asLong(), 2) : null;
    }

    /** Razorpay answers errors as { "error": { "code": ..., "description": ... } } with a 4xx / 5xx status. */
    private JsonNode call(Supplier<JsonNode> request, String what) {
        try {
            JsonNode body = request.get();
            if (body == null) {
                throw new GatewayException("Razorpay " + what + ": empty response", true);
            }
            return body;
        } catch (RestClientResponseException e) {
            log.error("Razorpay {} failed: HTTP {} {}", what, e.getStatusCode().value(), e.getResponseBodyAsString());
            // 409: an idempotent refund with the same key is still being processed; retrying later is safe
            int status = e.getStatusCode().value();
            throw new GatewayException("Razorpay " + what + " failed: HTTP " + status,
                    e.getStatusCode().is5xxServerError() || status == 429 || status == 409, e);
        } catch (ResourceAccessException e) {
            throw new GatewayException("Razorpay unreachable (" + what + "): " + e.getMessage(), true, e);
        } catch (RestClientException e) {
            throw new GatewayException("Razorpay " + what + " failed: " + e.getMessage(), true, e);
        }
    }

    /** A field as text; null when it is missing, null or blank. */
    public static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asString();
        return s.isBlank() ? null : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
