package com.uyirhealthapp.payment_service.webhook;

import com.uyirhealthapp.payment_service.common.PaymentMetrics;
import com.uyirhealthapp.payment_service.gateway.GatewayException;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.Outcome;
import com.uyirhealthapp.payment_service.linkedaccount.LinkedAccountService;
import com.uyirhealthapp.payment_service.payment.CheckoutSession;
import com.uyirhealthapp.payment_service.payment.CheckoutSessionRepository;
import com.uyirhealthapp.payment_service.payment.PaymentSettlement;
import com.uyirhealthapp.payment_service.refund.RefundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.UUID;

import static com.uyirhealthapp.payment_service.gateway.RazorpayPaymentGateway.fromPaise;
import static com.uyirhealthapp.payment_service.gateway.RazorpayPaymentGateway.text;

/**
 * Applies one verified Razorpay event. The event id (header x-razorpay-event-id) is recorded in the same
 * transaction as its effect: if applying fails, both roll back and Razorpay's retry gets another go; if it
 * succeeded, a retry is a no-op.
 *
 * <pre>
 *   { "entity": "event", "event": "payment_link.paid", "account_id": "acc_...",
 *     "payload": { "payment_link": { "entity": { "id": "plink_...", "amount_paid": 64000, "currency": "INR",
 *                                                "notes": { "payment_id": "&lt;our payment id&gt;", ... } } },
 *                  "payment": { "entity": { "id": "pay_...", "status": "captured", ... } } } }
 * </pre>
 *
 * payment.failed is not used: a failed attempt leaves the link open for another try, so only paid, expired and
 * cancelled end a checkout.
 */
@Service
@Slf4j
public class RazorpayWebhookHandler {

    /** Thrown when the event refers to something we do not know (yet): answer 5xx so Razorpay retries later. */
    public static class NotYetKnownException extends RuntimeException {
        public NotYetKnownException(String message) {
            super(message);
        }
    }

    private final ProviderEventRepository events;
    private final CheckoutSessionRepository sessions;
    private final PaymentSettlement settlement;
    private final RefundService refunds;
    private final LinkedAccountService linkedAccounts;
    private final PaymentMetrics metrics;
    private final Clock clock;

    public RazorpayWebhookHandler(ProviderEventRepository events, CheckoutSessionRepository sessions,
                                  PaymentSettlement settlement, RefundService refunds,
                                  LinkedAccountService linkedAccounts, PaymentMetrics metrics, Clock clock) {
        this.events = events;
        this.sessions = sessions;
        this.settlement = settlement;
        this.refunds = refunds;
        this.linkedAccounts = linkedAccounts;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public void handle(String eventId, JsonNode event) {
        String type = String.valueOf(text(event, "event"));
        if (events.existsById(eventId)) {
            log.debug("Razorpay event {} ({}) already processed", eventId, type);
            return;
        }
        JsonNode payload = event.path("payload");
        JsonNode link = payload.path("payment_link").path("entity");
        switch (type) {
            case "payment_link.paid" -> onPaid(link, payload.path("payment").path("entity"));
            case "payment_link.expired", "payment_link.cancelled" -> settlement.expired(text(link, "id"));
            case "refund.processed" -> onRefund(payload.path("refund").path("entity"), Outcome.SUCCEEDED);
            case "refund.failed" -> onRefund(payload.path("refund").path("entity"), Outcome.FAILED);
            case "product.route.activated", "product.route.under_review", "product.route.needs_clarification",
                 "product.route.suspended" -> onRouteProduct(event, payload.path("merchant_product").path("entity"));
            default -> log.info("Razorpay event {} ({}) not used, ignored", eventId, type);
        }
        events.save(new ProviderEvent(eventId, type, clock.instant()));
    }

    private void onPaid(JsonNode link, JsonNode payment) {
        String linkId = text(link, "id");
        String providerPaymentId = text(payment, "id");
        UUID paymentId = sessions.findByProviderSessionId(String.valueOf(linkId))
                .map(CheckoutSession::getPaymentId)
                .orElseGet(() -> parseUuid(text(link.path("notes"), "payment_id")));
        if (paymentId == null) {
            // not created by this service (e.g. a payment link made by hand in the Razorpay dashboard)
            log.warn("Razorpay payment {} (link {}) does not belong to any payment here, ignored", providerPaymentId,
                    linkId);
            metrics.anomaly("foreign_payment");
            return;
        }
        settlement.paid(paymentId, linkId, providerPaymentId, fromPaise(link.path("amount_paid")),
                text(link, "currency"));
    }

    private void onRefund(JsonNode refund, Outcome outcome) {
        String providerRefundId = text(refund, "id");
        String ourRef = text(refund.path("notes"), "refund_id");
        String reason = outcome == Outcome.FAILED ? "refund_failed_at_razorpay" : null;
        if (!refunds.onProviderUpdate(providerRefundId, ourRef, outcome, reason)) {
            if (ourRef != null) {
                // ours, but the POST /refund call has not stored Razorpay's refund id yet: let Razorpay retry
                throw new NotYetKnownException("refund " + providerRefundId + " not recorded yet");
            }
            log.warn("Razorpay refund {} not created by this service, ignored", providerRefundId);
        }
    }

    /**
     * A linked account's Route product changed. The event is only a hint: the account is re-read from Razorpay
     * with our own key, so its content is never trusted as the new status.
     * <pre>
     *   { "event": "product.route.activated", "account_id": "acc_...",
     *     "payload": { "merchant_product": { "entity": { "id": "acc_prd_...", "merchant_id": "...", ... } } } }
     * </pre>
     */
    private void onRouteProduct(JsonNode event, JsonNode product) {
        String productId = text(product, "id");
        String merchantId = text(product, "merchant_id");
        String accountId = merchantId != null
                ? (merchantId.startsWith("acc_") ? merchantId : "acc_" + merchantId)
                : text(event, "account_id");
        LinkedAccountService.UpdateResult result;
        try {
            result = linkedAccounts.onProviderUpdate(productId, accountId);
        } catch (GatewayException e) {
            throw new NotYetKnownException("linked account " + accountId + " could not be re-read: " + e.getMessage());
        }
        switch (result) {
            case UPDATED -> {
            }
            case BUSY -> throw new NotYetKnownException("linked account " + accountId + " is being onboarded");
            case NOT_OURS -> {
                log.warn("Razorpay route product {} (account {}) not created by this service, ignored", productId,
                        accountId);
                metrics.anomaly("foreign_linked_account");
            }
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
