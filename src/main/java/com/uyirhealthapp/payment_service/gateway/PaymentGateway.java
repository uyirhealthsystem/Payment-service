package com.uyirhealthapp.payment_service.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The payment provider, seen from this service. Implementations: {@link RazorpayPaymentGateway} (default) and
 * {@link MockPaymentGateway} (local testing). Failures surface as {@link GatewayException}.
 */
public interface PaymentGateway {

    /** What to charge. paymentId = our payment id, echoed back by the provider in webhooks. */
    record SessionRequest(UUID paymentId, BigDecimal amount, String currency, String description,
                          String referenceType, String referenceId, String successUrl) {
    }

    /**
     * A created checkout. instantPaymentId is set only when the payment is already settled at creation
     * (the mock provider); real providers report the result later via webhook.
     */
    record Session(String sessionId, String checkoutUrl, Instant expiresAt, String instantPaymentId) {
    }

    enum Outcome {
        OPEN, SUCCEEDED, FAILED, EXPIRED
    }

    /** Current state of a checkout, for reconciliation when a webhook went missing. */
    record SessionState(Outcome outcome, String providerPaymentId, BigDecimal amount, String currency) {
    }

    record RefundRequest(UUID refundId, String providerPaymentId, BigDecimal amount, String reasonCode,
                         String note) {
    }

    record RefundResult(String providerRefundId, Outcome outcome, String failureReason) {
    }

    /** Short name stored on each payment ("razorpay", "mock"). */
    String name();

    Session createSession(SessionRequest request);

    SessionState getSession(String sessionId);

    /** Makes a checkout unpayable, so a payment never has two payable checkouts. Fails if it was already paid. */
    void cancelSession(String sessionId);

    RefundResult refund(RefundRequest request);

    /**
     * Our refund as the provider sees it, for reconciliation when the refund call's answer or its webhook went
     * missing. providerRefundId may be null (the call never answered). null when the provider has no such refund.
     */
    RefundResult findRefund(UUID refundId, String providerPaymentId, String providerRefundId);
}
