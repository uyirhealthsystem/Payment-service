package com.uyirhealthapp.payment_service.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** All "payment.*" settings. Validated at startup, so a misconfigured deployment fails fast. */
@ConfigurationProperties(prefix = "payment")
@Validated
public record PaymentProperties(@DefaultValue("razorpay") @Pattern(regexp = "razorpay|mock") String provider,
                                @DefaultValue("INR") @NotBlank String currency,
                                @Valid @NotNull Security security,
                                @Valid @NotNull Razorpay razorpay,
                                @Valid @NotNull Checkout checkout,
                                @NotEmpty Map<String, @Valid Consumer> consumers,
                                @Valid @NotNull Delivery delivery,
                                @Valid @NotNull Reconcile reconcile) {

    public boolean isMock() {
        return "mock".equals(provider);
    }

    /** The consumer (calling module) registered for a referenceType such as AMBULANCE_BOOKING, or null. */
    public Consumer consumer(String referenceType) {
        return referenceType == null ? null : consumers.get(referenceType);
    }

    @AssertTrue(message = "payment.razorpay.key-id, key-secret and webhook-secret are required when "
            + "payment.provider=razorpay")
    public boolean isRazorpayConfigured() {
        return !"razorpay".equals(provider) || razorpay == null || razorpay.isComplete();
    }

    @AssertTrue(message = "payment.razorpay.key-id does not match payment.razorpay.live: live keys (rzp_live_...) "
            + "need RAZORPAY_LIVE=true, and test keys (rzp_test_...) are refused when it is set")
    public boolean isRazorpayKeyModeConsistent() {
        return !"razorpay".equals(provider) || razorpay == null || razorpay.isKeyModeConsistent();
    }

    /**
     * adminRoles: roles that may see any payment and issue refunds.
     * gatewaySecret: shared with the api-gateway; a request carrying it is authenticated from the gateway's
     * X-User-Id / X-User-Role headers without verifying a JWT here. jwksUri / publicKeyLocation then only matter
     * for callers that skip the gateway with a bearer token.
     * trustIdentityHeaders: DEVELOPMENT ONLY, believe X-User-Id / X-User-Role without any secret.
     */
    public record Security(String jwksUri, String publicKeyLocation, String issuer,
                           @DefaultValue("role") @NotBlank String roleClaim,
                           @DefaultValue({"ADMIN", "SUPER_ADMIN"}) @NotEmpty List<String> adminRoles,
                           String gatewaySecret,
                           @DefaultValue("false") boolean trustIdentityHeaders) {

        public boolean hasKeySource() {
            return StringUtils.hasText(jwksUri) || StringUtils.hasText(publicKeyLocation);
        }

        @AssertTrue(message = "set payment.security.gateway-secret (requests via the api-gateway) and/or "
                + "payment.security.jwks-uri / public-key-location (bearer tokens)")
        public boolean isAuthConfigured() {
            return hasKeySource() || StringUtils.hasText(gatewaySecret) || trustIdentityHeaders;
        }

        @AssertTrue(message = "payment.security.gateway-secret must be at least 32 characters when set")
        public boolean isGatewaySecretStrong() {
            return !StringUtils.hasText(gatewaySecret) || gatewaySecret.length() >= 32;
        }
    }

    /**
     * Razorpay (https://razorpay.com/docs/api/payments/payment-links/). Calls authenticate with the API key
     * (key id + secret, Basic auth); webhookSecret is the secret entered when the webhook is added in the
     * Razorpay dashboard. linkExpiry: how long a payment link stays payable (Razorpay requires at least 15m).
     * live: real money; must be set to use live keys (rzp_live_...), and refuses test keys, so neither kind ends up
     * in the wrong environment by accident. accountId (acc_..., optional): webhooks for any other account are
     * ignored, for when the webhook secret is shared between accounts.
     */
    public record Razorpay(@DefaultValue("https://api.razorpay.com/v1") @NotBlank String apiBaseUrl,
                           String keyId,
                           String keySecret,
                           String webhookSecret,
                           String accountId,
                           @DefaultValue("false") boolean live,
                           @DefaultValue("20m") @NotNull Duration linkExpiry,
                           @DefaultValue("3s") @NotNull Duration connectTimeout,
                           @DefaultValue("15s") @NotNull Duration readTimeout) {

        boolean isComplete() {
            return StringUtils.hasText(keyId) && StringUtils.hasText(keySecret) && StringUtils.hasText(webhookSecret);
        }

        boolean isKeyModeConsistent() {
            return !StringUtils.hasText(keyId) || keyId.startsWith(live ? "rzp_live_" : "rzp_test_");
        }

        @AssertTrue(message = "payment.razorpay.link-expiry must be between 16m and 24h (Razorpay requires more "
                + "than 15 minutes)")
        public boolean isLinkExpiryInRange() {
            return linkExpiry.compareTo(Duration.ofMinutes(16)) >= 0 && linkExpiry.compareTo(Duration.ofHours(24)) <= 0;
        }
    }

    /**
     * Where Razorpay's hosted checkout sends the browser after paying (Razorpay appends the result as query
     * parameters). A consumer can override it with its own returnUrl. minRemaining: an open checkout with less
     * time than this left is not handed out again; a fresh one is created instead.
     */
    public record Checkout(@NotBlank String successUrl,
                           @DefaultValue("2m") @NotNull Duration minRemaining) {
    }

    /**
     * A module that takes payments, keyed by its referenceType (AMBULANCE_BOOKING, DOCTOR_APPOINTMENT, ...).
     * The result of each payment is POSTed to webhookUrl, signed with webhookSecret. returnUrl: optional
     * per-module override of checkout.successUrl.
     */
    public record Consumer(String webhookUrl, String webhookSecret, String returnUrl) {

        public boolean notifies() {
            return StringUtils.hasText(webhookUrl);
        }

        @AssertTrue(message = "webhook-secret is required when webhook-url is set")
        public boolean isSecretSetWithUrl() {
            return !StringUtils.hasText(webhookUrl) || StringUtils.hasText(webhookSecret);
        }
    }

    /** Outbound result callbacks to the modules: retried with exponential backoff, then given up. */
    public record Delivery(@DefaultValue("12") @Min(1) int maxAttempts,
                           @DefaultValue("10s") @NotNull Duration initialBackoff,
                           @DefaultValue("1h") @NotNull Duration maxBackoff,
                           @DefaultValue("20") @Min(1) @Max(200) int batchSize,
                           @DefaultValue("3s") @NotNull Duration connectTimeout,
                           @DefaultValue("10s") @NotNull Duration readTimeout) {
    }

    /**
     * Safety net for lost webhooks: checkouts still open after "after" are looked up at the provider; after
     * "giveUpAfter" they are closed as expired without asking. PENDING refunds are looked up every "refundAfter"
     * until final; one the provider still does not know "refundNotFoundAfter" after it was requested was never
     * created there, and is marked FAILED so its amount can be refunded again.
     */
    public record Reconcile(@DefaultValue("true") boolean enabled,
                            @DefaultValue("3m") @NotNull Duration after,
                            @DefaultValue("24h") @NotNull Duration giveUpAfter,
                            @DefaultValue("50") @Min(1) @Max(500) int batchSize,
                            @DefaultValue("15m") @NotNull Duration refundAfter,
                            @DefaultValue("30m") @NotNull Duration refundNotFoundAfter) {
    }
}
