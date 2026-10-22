package com.uyirhealthapp.payment_service.payment;

import com.uyirhealthapp.payment_service.refund.Refund;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * A module asks: "this user owes this much for this booking". referenceType must be a module registered in
     * payment.consumers (AMBULANCE_BOOKING, DOCTOR_APPOINTMENT, ...). description is shown on the checkout page.
     */
    public record CreateIntentRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String referenceType,
            @NotBlank @Size(max = 128) String referenceId,
            @NotBlank @Size(max = 128) String payerId,
            @NotNull @DecimalMin("1.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Size(max = 500) String description) {
    }

    /**
     * A payment as every caller sees it. The first three fields are what POST /intents callers need:
     * id, status and, while PENDING, the checkoutUrl to send the user to (valid until checkoutExpiresAt).
     */
    public record PaymentView(UUID id, PaymentStatus status, String checkoutUrl, Instant checkoutExpiresAt,
                              String referenceType, String referenceId, String payerId, BigDecimal amount,
                              String currency, BigDecimal refundedAmount, String failureReason, Instant createdAt,
                              Instant paidAt, List<RefundView> refunds) {
    }

    /** amount: omit to refund everything not yet refunded. */
    public record RefundRequest(@DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                @NotNull Refund.Reason reason,
                                @Size(max = 500) String note) {
    }

    public record RefundView(UUID id, BigDecimal amount, Refund.Reason reason, Refund.Status status,
                             String failureReason, String requestedBy, Instant createdAt) {

        public static RefundView of(Refund r) {
            return new RefundView(r.getId(), r.getAmount(), r.getReason(), r.getStatus(), r.getFailureReason(),
                    r.getRequestedBy(), r.getCreatedAt());
        }
    }
}
