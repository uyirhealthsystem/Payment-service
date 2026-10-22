package com.uyirhealthapp.payment_service.gateway;

import com.uyirhealthapp.payment_service.config.PaymentProperties;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.AccountRequest;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.Activation;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.Address;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.BankAccount;
import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.ProductState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RazorpayLinkedAccountGatewayTest {

    private static final String V2 = "https://api.razorpay.com/v2";

    private MockRestServiceServer server;
    private RazorpayLinkedAccountGateway gateway;

    @BeforeEach
    void setUp() {
        PaymentProperties props = mock(PaymentProperties.class);
        when(props.razorpay()).thenReturn(new PaymentProperties.Razorpay("https://api.razorpay.com/v1",
                "rzp_test_key", "key-secret", "webhook-secret", null, false, Duration.ofMinutes(20),
                Duration.ofSeconds(3), Duration.ofSeconds(15)));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RazorpayLinkedAccountGateway(props, builder);
    }

    @Test
    void v2BaseUrlIsDerivedFromTheV1One() {
        assertThat(RazorpayLinkedAccountGateway.v2("https://api.razorpay.com/v1")).isEqualTo(V2);
        assertThat(RazorpayLinkedAccountGateway.v2("https://api.razorpay.com/v1/")).isEqualTo(V2);
        assertThat(RazorpayLinkedAccountGateway.v2("http://localhost:9000")).isEqualTo("http://localhost:9000/v2");
    }

    @Test
    void createsARouteAccountWithTheRegisteredAddress() {
        server.expect(requestTo(V2 + "/accounts")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic cnpwX3Rlc3Rfa2V5OmtleS1zZWNyZXQ="))
                .andExpect(jsonPath("$.type").value("route"))
                .andExpect(jsonPath("$.legal_business_name").value("Sri Ram Hospital Pvt Ltd"))
                .andExpect(jsonPath("$.business_type").value("private_limited"))
                .andExpect(jsonPath("$.profile.category").value("healthcare"))
                .andExpect(jsonPath("$.profile.subcategory").value("hospitals"))
                .andExpect(jsonPath("$.profile.addresses.registered.state").value("TAMIL NADU"))
                .andExpect(jsonPath("$.profile.addresses.registered.street2").value("12 Anna Salai"))
                .andExpect(jsonPath("$.profile.addresses.registered.postal_code").value("600002"))
                .andExpect(jsonPath("$.legal_info.pan").value("AAACS1234C"))
                .andExpect(jsonPath("$.legal_info.gst").doesNotExist())
                .andRespond(withSuccess("""
                        {"id":"acc_Hosp123","type":"route","status":"created"}""", MediaType.APPLICATION_JSON));

        String id = gateway.createAccount(new AccountRequest("accounts@sriram.in", "9876543210",
                "Sri Ram Hospital Pvt Ltd", "private_limited", "Ravi Kumar", "healthcare", "hospitals",
                new Address("12 Anna Salai", null, "Chennai", "Tamil Nadu", "600002", "IN"), "AAACS1234C", null));

        assertThat(id).isEqualTo("acc_Hosp123");
        server.verify();
    }

    @Test
    void bankAccountIsPatchedAndRequirementsAreReported() {
        server.expect(requestTo(V2 + "/accounts/acc_Hosp123/products/acc_prd_1")).andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.settlements.account_number").value("123456789012"))
                .andExpect(jsonPath("$.settlements.ifsc_code").value("HDFC0000317"))
                .andExpect(jsonPath("$.tnc_accepted").value(true))
                .andRespond(withSuccess("""
                        {"id":"acc_prd_1","activation_status":"needs_clarification",
                         "requirements":[{"field_reference":"settlements.beneficiary_name","status":"required",
                                          "reason_code":"field_missing"}]}""", MediaType.APPLICATION_JSON));

        ProductState state = gateway.submitBankAccount("acc_Hosp123", "acc_prd_1",
                new BankAccount("123456789012", "HDFC0000317", "Sri Ram Hospital"));

        assertThat(state.activation()).isEqualTo(Activation.NEEDS_CLARIFICATION);
        assertThat(state.requirements()).containsExactly("settlements.beneficiary_name (field_missing)");
    }

    @Test
    void activationStatusesMapToOurs() {
        assertThat(RazorpayLinkedAccountGateway.activation("activated")).isEqualTo(Activation.ACTIVATED);
        assertThat(RazorpayLinkedAccountGateway.activation("under_review")).isEqualTo(Activation.UNDER_REVIEW);
        assertThat(RazorpayLinkedAccountGateway.activation("suspended")).isEqualTo(Activation.SUSPENDED);
        assertThat(RazorpayLinkedAccountGateway.activation("requested")).isEqualTo(Activation.PENDING_DETAILS);
    }

    @Test
    void rejectedDetailsCarryRazorpaysReason() {
        server.expect(requestTo(V2 + "/accounts")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"error":{"code":"BAD_REQUEST_ERROR","description":"The pan field is invalid.","field":"pan"}}"""));

        assertThatThrownBy(() -> gateway.createAccount(new AccountRequest("a@b.in", "9876543210", "Some Hospital",
                "trust", "Ravi Kumar", "healthcare", "hospitals",
                new Address("Street", null, "Chennai", "Tamil Nadu", "600002", "IN"), "BADPAN1234", null)))
                .isInstanceOf(DetailsRejectedException.class)
                .hasMessageContaining("The pan field is invalid.");
    }

    @Test
    void serverErrorsAreRetryable() {
        server.expect(requestTo(V2 + "/accounts/acc_1/products/acc_prd_1"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> gateway.getRoute("acc_1", "acc_prd_1"))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.isRetryable()).isTrue())
                .isNotInstanceOf(DetailsRejectedException.class);
    }
}
