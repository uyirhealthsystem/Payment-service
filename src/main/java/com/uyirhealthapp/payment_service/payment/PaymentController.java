package com.uyirhealthapp.payment_service.payment;

import com.uyirhealthapp.payment_service.config.SecurityConfig;
import com.uyirhealthapp.payment_service.payment.PaymentDtos.CreateIntentRequest;
import com.uyirhealthapp.payment_service.payment.PaymentDtos.PaymentView;
import com.uyirhealthapp.payment_service.payment.PaymentDtos.RefundRequest;
import com.uyirhealthapp.payment_service.payment.PaymentDtos.RefundView;
import com.uyirhealthapp.payment_service.refund.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SecurityConfig.API)
@Tag(name = "payments")
@SecurityRequirement(name = "bearer")
public class PaymentController {

    private final PaymentService payments;
    private final RefundService refunds;

    public PaymentController(PaymentService payments, RefundService refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    @Operation(summary = "Create (or return the existing) payment for a booking and get a checkout URL",
            description = "Called by a module with the payer's token. The same Idempotency-Key always returns the "
                    + "same payment, e.g. \"ambulance-booking-{bookingId}\".")
    @PostMapping("/intents")
    public PaymentView createIntent(@AuthenticationPrincipal Jwt jwt,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @Valid @RequestBody CreateIntentRequest request) {
        return payments.createIntent(request, idempotencyKey, jwt);
    }

    @GetMapping("/{id}")
    public PaymentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.get(id, jwt);
    }

    @Operation(summary = "Payments for one booking of one module")
    @GetMapping
    public List<PaymentView> byReference(@AuthenticationPrincipal Jwt jwt, @RequestParam String referenceType,
                                         @RequestParam String referenceId) {
        return payments.findByReference(referenceType, referenceId, jwt);
    }

    @Operation(summary = "Ask the payment provider for the result now",
            description = "For the app to call when the user is back from the checkout page, instead of waiting "
                    + "for the provider's webhook.")
    @PostMapping("/{id}/refresh")
    public PaymentView refresh(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return payments.refresh(id, jwt);
    }

    @Operation(summary = "Refund a PAID payment (ADMIN / SUPER_ADMIN)")
    @PostMapping("/{id}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundView refund(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                             @Valid @RequestBody RefundRequest request) {
        return refunds.refund(id, request, jwt.getSubject());
    }
}
