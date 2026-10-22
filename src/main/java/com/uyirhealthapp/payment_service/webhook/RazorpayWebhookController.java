package com.uyirhealthapp.payment_service.webhook;

import com.uyirhealthapp.payment_service.common.ApiException;
import com.uyirhealthapp.payment_service.common.Hmac;
import com.uyirhealthapp.payment_service.common.PaymentMetrics;
import com.uyirhealthapp.payment_service.config.PaymentProperties;
import com.uyirhealthapp.payment_service.config.SecurityConfig;
import com.uyirhealthapp.payment_service.gateway.RazorpayPaymentGateway;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Razorpay calls this for payment link and refund events. There is no user token; it is authenticated by
 * X-Razorpay-Signature = hex(HMAC-SHA256(webhook secret, raw body)). Razorpay signs no timestamp, so a replay is
 * stopped by the event id (x-razorpay-event-id) being processed only once, and every effect is idempotent anyway.
 * Register in Razorpay Dashboard > Accounts &amp; Settings > Webhooks:
 * {@code https://<gateway host>/api/payments/webhooks/razorpay}, events payment_link.paid, payment_link.expired,
 * payment_link.cancelled, refund.processed, refund.failed, and for linked accounts product.route.activated,
 * product.route.under_review, product.route.needs_clarification, product.route.suspended.
 */
@RestController
@RequestMapping(SecurityConfig.WEBHOOKS)
@Tag(name = "provider webhooks")
@Slf4j
public class RazorpayWebhookController {

    private final RazorpayWebhookHandler handler;
    private final JsonMapper json;
    private final PaymentMetrics metrics;
    private final String secret;
    private final String accountId;

    public RazorpayWebhookController(RazorpayWebhookHandler handler, JsonMapper json, PaymentMetrics metrics,
                                     PaymentProperties props) {
        this.handler = handler;
        this.json = json;
        this.metrics = metrics;
        this.secret = props.razorpay().webhookSecret();
        this.accountId = props.razorpay().accountId();
    }

    @PostMapping("/razorpay")
    public ResponseEntity<Map<String, Boolean>> razorpay(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String sig,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId,
            @RequestBody byte[] raw) {
        if (!StringUtils.hasText(secret)) {
            throw ApiException.serviceUnavailable("Razorpay webhook is not configured");
        }
        if (!Hmac.matches(Hmac.sign(secret, raw), sig)) {
            log.warn("Rejected Razorpay webhook: bad signature");
            throw ApiException.unauthorized("Bad signature");
        }
        JsonNode event;
        try {
            event = json.readTree(raw);
        } catch (JacksonException e) {
            throw ApiException.badRequest("Malformed body");
        }
        String eventAccount = RazorpayPaymentGateway.text(event, "account_id");
        // product.route.* events are about a linked account and may carry its id instead of ours; they are safe to
        // let through because the handler only re-reads accounts it created, from Razorpay, with our own key
        boolean routeEvent = String.valueOf(RazorpayPaymentGateway.text(event, "event")).startsWith("product.route.");
        if (StringUtils.hasText(accountId) && !accountId.equals(eventAccount) && !routeEvent) {
            // genuine (signed) but for another Razorpay account sharing the secret: 200 so Razorpay does not retry
            log.warn("Razorpay webhook for account {} ignored, this service uses {}", eventAccount, accountId);
            metrics.anomaly("foreign_account");
            return ResponseEntity.ok(Map.of("ok", true));
        }
        try {
            // Razorpay always sends the event id; the body's hash is only a stand-in that still dedupes exact retries
            handler.handle(StringUtils.hasText(eventId) ? eventId.trim() : sha256(raw), event);
        } catch (RazorpayWebhookHandler.NotYetKnownException e) {
            log.info("Razorpay webhook deferred: {}", e.getMessage());
            throw ApiException.serviceUnavailable("Not ready yet, retry later");
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String sha256(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
