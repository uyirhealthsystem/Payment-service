package com.uyirhealthapp.payment_service.notify;

import com.uyirhealthapp.payment_service.payment.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Body of the result callback POSTed to a module's webhook-url:
 *
 * <pre>
 *   X-Payment-Timestamp: 1727222400                                         (epoch seconds)
 *   X-Payment-Signature: hex(HMAC-SHA256(webhookSecret, timestamp + "." + rawBody))
 *   { "paymentId": "...", "referenceType": "AMBULANCE_BOOKING", "referenceId": "...", "bookingId": "...",
 *     "status": "PAID", "amount": 640.00, "currency": "INR", ... }
 * </pre>
 *
 * bookingId: DEPRECATED alias of referenceId, kept only because existing consumers read it. New modules must
 * use referenceId; drop bookingId once no consumer reads it. Modules must check that amount covers
 * what they charge; the payer's app is what asked for the payment, so the amount is only as trustworthy as
 * the module's own check.
 */
public record PaymentEvent(UUID paymentId, String referenceType, String referenceId, String bookingId,
                           String payerId, PaymentStatus status, BigDecimal amount, String currency,
                           BigDecimal refundedAmount, String failureReason, Instant occurredAt) {
}
