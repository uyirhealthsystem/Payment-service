package com.uyirhealthapp.payment_service.gateway;

import com.uyirhealthapp.payment_service.config.PaymentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.uyirhealthapp.payment_service.gateway.RazorpayPaymentGateway.text;

/**
 * Razorpay Route linked accounts (https://razorpay.com/docs/api/payments/route/linked-account/), API v2 with the
 * same key as the payment links. Route must be enabled on the Razorpay account first.
 *
 * <pre>
 *   POST  /v2/accounts                                  create the linked account -> acc_...
 *   POST  /v2/accounts/{acc}/stakeholders               owner / signatory          -> sth_...
 *   POST  /v2/accounts/{acc}/products                   request Route              -> acc_prd_..., activation_status
 *   PATCH /v2/accounts/{acc}/products/{prd}             bank account (settlements)
 *   GET   /v2/accounts/{acc}/products/{prd}             activation_status + requirements (reconciliation)
 * </pre>
 *
 * A 4xx is Razorpay rejecting the details; its error.description (e.g. "Invalid IFSC code") is passed on so the
 * hospital can fix them. Bank account numbers are never logged.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "razorpay", matchIfMissing = true)
@Slf4j
public class RazorpayLinkedAccountGateway implements LinkedAccountGateway {

    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public RazorpayLinkedAccountGateway(PaymentProperties props) {
        this(props, RestClient.builder().requestFactory(requestFactory(props.razorpay())));
    }

    /** For tests: lets a mock server be bound to the builder. */
    RazorpayLinkedAccountGateway(PaymentProperties props, RestClient.Builder builder) {
        PaymentProperties.Razorpay cfg = props.razorpay();
        String basic = Base64.getEncoder()
                .encodeToString((cfg.keyId() + ":" + cfg.keySecret()).getBytes(StandardCharsets.UTF_8));
        this.http = builder.baseUrl(v2(cfg.apiBaseUrl())).defaultHeader("Authorization", "Basic " + basic).build();
    }

    /** java.net.http, not HttpURLConnection: the bank account is set with PATCH, which HttpURLConnection lacks. */
    private static JdkClientHttpRequestFactory requestFactory(PaymentProperties.Razorpay cfg) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(cfg.connectTimeout()).build());
        factory.setReadTimeout(cfg.readTimeout());
        return factory;
    }

    /** payment.razorpay.api-base-url points at v1 (payment links); linked accounts only exist in v2. */
    static String v2(String apiBaseUrl) {
        String base = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        return base.endsWith("/v1") ? base.substring(0, base.length() - 3) + "/v2" : base + "/v2";
    }

    @Override
    public String createAccount(AccountRequest r) {
        Map<String, Object> registered = new LinkedHashMap<>();
        registered.put("street1", r.address().street1());
        registered.put("street2", r.address().street2() != null ? r.address().street2() : r.address().street1());
        registered.put("city", r.address().city());
        registered.put("state", r.address().state().toUpperCase());
        registered.put("postal_code", r.address().postalCode());
        registered.put("country", r.address().country());

        Map<String, Object> legal = new LinkedHashMap<>();
        legal.put("pan", r.pan());
        if (r.gst() != null) {
            legal.put("gst", r.gst());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", r.email());
        body.put("phone", r.phone());
        body.put("type", "route");
        body.put("legal_business_name", r.legalBusinessName());
        body.put("business_type", r.businessType());
        body.put("contact_name", r.contactName());
        body.put("profile", Map.of(
                "category", r.category(),
                "subcategory", r.subcategory(),
                "addresses", Map.of("registered", registered)));
        body.put("legal_info", legal);

        JsonNode account = call(() -> http.post().uri("/accounts").body(body).retrieve().body(JsonNode.class),
                "create linked account");
        return required(account, "id", "linked account");
    }

    @Override
    public String createStakeholder(String accountId, StakeholderRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", r.name());
        body.put("email", r.email());
        if (r.phone() != null) {
            body.put("phone", Map.of("primary", r.phone()));
        }
        body.put("kyc", Map.of("pan", r.pan()));

        JsonNode stakeholder = call(() -> http.post().uri("/accounts/{acc}/stakeholders", accountId).body(body)
                .retrieve().body(JsonNode.class), "create stakeholder");
        return required(stakeholder, "id", "stakeholder");
    }

    @Override
    public ProductState requestRoute(String accountId) {
        Map<String, Object> body = Map.of("product_name", "route", "tnc_accepted", true);
        return product(call(() -> http.post().uri("/accounts/{acc}/products", accountId).body(body)
                .retrieve().body(JsonNode.class), "request route product"));
    }

    @Override
    public ProductState submitBankAccount(String accountId, String productId, BankAccount bank) {
        Map<String, Object> body = Map.of(
                "settlements", Map.of(
                        "account_number", bank.accountNumber(),
                        "ifsc_code", bank.ifscCode(),
                        "beneficiary_name", bank.beneficiaryName()),
                "tnc_accepted", true);
        return product(call(() -> http.patch().uri("/accounts/{acc}/products/{prd}", accountId, productId)
                .body(body).retrieve().body(JsonNode.class), "submit bank account"));
    }

    @Override
    public ProductState getRoute(String accountId, String productId) {
        return product(call(() -> http.get().uri("/accounts/{acc}/products/{prd}", accountId, productId)
                .retrieve().body(JsonNode.class), "get route product"));
    }

    static ProductState product(JsonNode product) {
        List<String> requirements = new ArrayList<>();
        for (JsonNode req : product.path("requirements")) {
            String field = text(req, "field_reference");
            String reason = text(req, "reason_code");
            if (field != null) {
                requirements.add(reason != null ? field + " (" + reason + ")" : field);
            }
        }
        return new ProductState(required(product, "id", "route product"),
                activation(text(product, "activation_status")), List.copyOf(requirements));
    }

    /** Razorpay activation_status -> ours. "requested": the product exists but details are still missing. */
    static Activation activation(String status) {
        return switch (String.valueOf(status).toLowerCase()) {
            case "activated" -> Activation.ACTIVATED;
            case "under_review" -> Activation.UNDER_REVIEW;
            case "needs_clarification" -> Activation.NEEDS_CLARIFICATION;
            case "suspended" -> Activation.SUSPENDED;
            default -> Activation.PENDING_DETAILS;
        };
    }

    private static String required(JsonNode node, String field, String what) {
        String value = text(node, field);
        if (value == null) {
            throw new GatewayException("Razorpay returned a " + what + " without " + field, false);
        }
        return value;
    }

    private JsonNode call(Supplier<JsonNode> request, String what) {
        try {
            JsonNode body = request.get();
            if (body == null) {
                throw new GatewayException("Razorpay " + what + ": empty response", true);
            }
            return body;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String description = errorDescription(e.getResponseBodyAsString());
            // the response body may echo what was sent (bank account number): log only Razorpay's description
            log.error("Razorpay {} failed: HTTP {} {}", what, status, description);
            boolean retryable = e.getStatusCode().is5xxServerError() || status == 429;
            if (!retryable && status != 401 && status != 403 && description != null) {
                throw new DetailsRejectedException("Razorpay rejected the details (" + what + "): " + description, e);
            }
            // 401 / 403: our key or Route access is wrong, nothing the hospital can fix
            throw new GatewayException("Razorpay " + what + " failed: HTTP " + status, retryable, e);
        } catch (ResourceAccessException e) {
            throw new GatewayException("Razorpay unreachable (" + what + "): " + e.getMessage(), true, e);
        } catch (RestClientException e) {
            throw new GatewayException("Razorpay " + what + " failed: " + e.getMessage(), true, e);
        }
    }

    /** { "error": { "code": "BAD_REQUEST_ERROR", "description": "...", "field": "..." } } -> description. */
    private String errorDescription(String body) {
        try {
            return text(json.readTree(body).path("error"), "description");
        } catch (RuntimeException e) {
            return null;
        }
    }
}
