package com.uyirhealthapp.payment_service;

import com.uyirhealthapp.payment_service.common.Hmac;
import com.uyirhealthapp.payment_service.gateway.DetailsRejectedException;
import com.uyirhealthapp.payment_service.gateway.GatewayException;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.Activation;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.ProductState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Linked-account onboarding end to end against a real Postgres, with Razorpay Route stubbed. */
@SpringBootTest(properties = {
        "payment.provider=mock",
        "payment.security.jwks-uri=http://jwks.invalid/.well-known/jwks.json",
        "payment.checkout.success-url=https://app.example/payments/result",
        "payment.razorpay.webhook-secret=" + LinkedAccountFlowIT.RAZORPAY_SECRET,
        "payment.razorpay.account-id=acc_platform",
        "payment.reconcile.enabled=false",
})
@AutoConfigureMockMvc
@Testcontainers
class LinkedAccountFlowIT {

    static final String RAZORPAY_SECRET = "it-razorpay-webhook-secret";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @MockitoBean LinkedAccountGateway gateway;

    private String accountId;
    private String productId;

    @BeforeEach
    void setUp() {
        accountId = "acc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        productId = "acc_prd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        when(gateway.createAccount(any())).thenReturn(accountId);
        when(gateway.createStakeholder(eq(accountId), any())).thenReturn("sth_1");
        when(gateway.requestRoute(accountId)).thenReturn(new ProductState(productId, Activation.PENDING_DETAILS,
                List.of("settlements.account_number (field_missing)")));
        when(gateway.submitBankAccount(eq(accountId), eq(productId), any()))
                .thenReturn(new ProductState(productId, Activation.UNDER_REVIEW, List.of()));
    }

    @Test
    void onboardThenActivatedByWebhook() throws Exception {
        String hospitalUser = "hosp-user-" + UUID.randomUUID();

        JsonNode view = body(onboard(hospitalUser, "HOSPITAL", "hospital-1", "123456789012")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.providerAccountId").value(accountId))
                .andExpect(jsonPath("$.bankAccountLast4").value("9012"))
                .andExpect(jsonPath("$.accountNumber").doesNotExist()));
        String id = view.get("id").asString();

        // same details again: nothing is sent to Razorpay twice
        onboard(hospitalUser, "HOSPITAL", "hospital-1", "123456789012").andExpect(status().isOk());
        verify(gateway, times(1)).createAccount(any());
        verify(gateway, times(1)).submitBankAccount(anyString(), anyString(), any());

        // Razorpay activates it; the webhook is only a hint, the status is re-read with our key
        when(gateway.getRoute(accountId, productId)).thenReturn(new ProductState(productId, Activation.ACTIVATED,
                List.of()));
        String event = """
                {"entity":"event","event":"product.route.activated","account_id":"%s","payload":{
                  "merchant_product":{"entity":{"id":"%s","merchant_id":"%s","activation_status":"activated"}}}}"""
                .formatted(accountId, productId, accountId.substring(4));
        razorpayWebhook(event, "evt_" + UUID.randomUUID()).andExpect(status().isOk());

        mvc.perform(get("/api/payments/linked-accounts").param("ownerType", "HOSPITAL")
                        .with(user(hospitalUser, "HOSPITAL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.activatedAt").isNotEmpty());

        // a changed bank account is submitted again
        onboard(hospitalUser, "HOSPITAL", "hospital-1", "999988887777")
                .andExpect(jsonPath("$.bankAccountLast4").value("7777"));
        verify(gateway, times(2)).submitBankAccount(anyString(), anyString(), any());

        // nobody else sees it
        mvc.perform(get("/api/payments/linked-accounts/{id}", id).with(user("other", "HOSPITAL")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/linked-accounts/{id}", id).with(user("admin-1", "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void aFailedStepResumesWithoutASecondRazorpayAccount() throws Exception {
        String hospitalUser = "hosp-user-" + UUID.randomUUID();
        when(gateway.createStakeholder(eq(accountId), any()))
                .thenThrow(new GatewayException("Razorpay unreachable", true))
                .thenReturn("sth_1");

        onboard(hospitalUser, "HOSPITAL", "hospital-2", "123456789012").andExpect(status().isServiceUnavailable());
        onboard(hospitalUser, "HOSPITAL", "hospital-2", "123456789012")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        verify(gateway, times(1)).createAccount(any());
    }

    @Test
    void razorpaysReasonIsPassedOnWhenDetailsAreRejected() throws Exception {
        when(gateway.createAccount(any())).thenThrow(
                new DetailsRejectedException("Razorpay rejected the details (create linked account): "
                        + "The pan field is invalid.", null));

        onboard("hosp-user-" + UUID.randomUUID(), "HOSPITAL", "hospital-3", "123456789012")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("pan field is invalid")));
    }

    @Test
    void onlyConfiguredOwnerTypesAndTheirRole() throws Exception {
        onboard("patient-1", "HOSPITAL", "hospital-4", "123456789012").andExpect(status().isForbidden());
        mvc.perform(put("/api/payments/linked-accounts").with(user("x", "LAB"))
                        .contentType(MediaType.APPLICATION_JSON).content(onboardBody("LAB", "lab-1", "123456789012")))
                .andExpect(status().isBadRequest());
        verify(gateway, never()).createAccount(any());
    }

    @Test
    void invalidDetailsAreRejectedBeforeRazorpay() throws Exception {
        String body = onboardBody("HOSPITAL", "hospital-5", "123456789012")
                .replace("\"HDFC0000317\"", "\"HDFC317\"")
                .replace("\"tncAccepted\":true", "\"tncAccepted\":false");
        mvc.perform(put("/api/payments/linked-accounts").with(user("hosp-user-x", "HOSPITAL"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("bankAccount.ifscCode")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("tncAccepted")));
    }

    // ---------------------------------------------------------------------------------------- helpers

    private ResultActions onboard(String userId, String ownerType, String referenceId, String accountNumber)
            throws Exception {
        return mvc.perform(put("/api/payments/linked-accounts").with(user(userId, userId.startsWith("patient")
                        ? "PATIENT" : "HOSPITAL"))
                .contentType(MediaType.APPLICATION_JSON).content(onboardBody(ownerType, referenceId, accountNumber)));
    }

    private static String onboardBody(String ownerType, String referenceId, String accountNumber) {
        return """
                {"ownerType":"%s","referenceId":"%s","legalBusinessName":"Sri Ram Hospital Pvt Ltd",
                 "businessType":"private_limited","contactName":"Ravi Kumar","email":"accounts@sriram.in",
                 "phone":"9876543210","category":"healthcare","subcategory":"hospitals",
                 "address":{"street1":"12 Anna Salai","city":"Chennai","state":"Tamil Nadu","postalCode":"600002"},
                 "pan":"AAACS1234C",
                 "stakeholder":{"name":"Ravi Kumar","email":"ravi@sriram.in","pan":"ABCPK1234D"},
                 "bankAccount":{"accountNumber":"%s","ifscCode":"HDFC0000317","beneficiaryName":"Sri Ram Hospital"},
                 "tncAccepted":true}""".formatted(ownerType, referenceId, accountNumber);
    }

    private ResultActions razorpayWebhook(String body, String eventId) throws Exception {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post("/api/payments/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(raw)
                .header("X-Razorpay-Signature", Hmac.sign(RAZORPAY_SECRET, raw))
                .header("X-Razorpay-Event-Id", eventId));
    }

    private static RequestPostProcessor user(String id, String role) {
        return jwt().jwt(j -> j.subject(id).claim("role", role))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
