package com.uyirhealthapp.payment_service.gateway;

import com.uyirhealthapp.payment_service.config.PaymentProperties;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.Outcome;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.RefundRequest;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.Session;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.SessionRequest;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

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

class RazorpayPaymentGatewayTest {

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final String API = "https://api.razorpay.com/v1";

    private MockRestServiceServer server;
    private RazorpayPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        PaymentProperties props = mock(PaymentProperties.class);
        when(props.razorpay()).thenReturn(new PaymentProperties.Razorpay(API, "rzp_test_key", "key-secret",
                "webhook-secret", null, false, Duration.ofMinutes(20), Duration.ofSeconds(3), Duration.ofSeconds(15)));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RazorpayPaymentGateway(props, Clock.fixed(NOW, ZoneOffset.UTC), builder);
    }

    @Test
    void createsAPaymentLinkInPaiseWithOurPaymentIdInTheNotes() {
        UUID paymentId = UUID.randomUUID();
        server.expect(requestTo(API + "/payment_links")).andExpect(method(HttpMethod.POST))
                // Basic base64("rzp_test_key:key-secret")
                .andExpect(header("Authorization", "Basic cnpwX3Rlc3Rfa2V5OmtleS1zZWNyZXQ="))
                .andExpect(jsonPath("$.amount").value(64050))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.expire_by").value(NOW.plus(Duration.ofMinutes(20)).getEpochSecond()))
                .andExpect(jsonPath("$.notes.payment_id").value(paymentId.toString()))
                .andExpect(jsonPath("$.notes.reference_type").value("AMBULANCE_BOOKING"))
                .andExpect(jsonPath("$.reference_id").doesNotExist())
                .andExpect(jsonPath("$.accept_partial").doesNotExist())
                .andExpect(jsonPath("$.notify.sms").value(false))
                .andExpect(jsonPath("$.callback_url").value("https://app.uyir.in/payments/result"))
                .andExpect(jsonPath("$.callback_method").value("get"))
                .andRespond(withSuccess("""
                        {"id":"plink_Abc123","short_url":"https://rzp.io/i/xyz","status":"created",
                         "expire_by":1791458400}""", MediaType.APPLICATION_JSON));

        Session session = gateway.createSession(new SessionRequest(paymentId, new BigDecimal("640.50"), "INR",
                "Ambulance ride", "AMBULANCE_BOOKING", "booking-1", "https://app.uyir.in/payments/result"));

        assertThat(session.sessionId()).isEqualTo("plink_Abc123");
        assertThat(session.checkoutUrl()).isEqualTo("https://rzp.io/i/xyz");
        assertThat(session.expiresAt()).isEqualTo(Instant.ofEpochSecond(1791458400));
        assertThat(session.instantPaymentId()).isNull();
        server.verify();
    }

    @Test
    void paidLinkReportsTheCapturedPaymentAndTheAmountInRupees() {
        server.expect(requestTo(API + "/payment_links/plink_Abc123")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"plink_Abc123","status":"paid","amount":64000,"amount_paid":64000,"currency":"INR",
                         "expire_by":1791458400,
                         "payments":[{"payment_id":"pay_Xyz","amount":64000,"status":"captured","method":"upi"}]}""",
                        MediaType.APPLICATION_JSON));

        SessionState state = gateway.getSession("plink_Abc123");

        assertThat(state.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(state.providerPaymentId()).isEqualTo("pay_Xyz");
        assertThat(state.amount()).isEqualByComparingTo("640.00");
        assertThat(state.currency()).isEqualTo("INR");
    }

    @Test
    void unpaidLinkIsOpenUntilItExpires() {
        server.expect(requestTo(API + "/payment_links/plink_open")).andRespond(withSuccess("""
                {"id":"plink_open","status":"created","amount":64000,"amount_paid":0,"currency":"INR",
                 "expire_by":%d,"payments":null}""".formatted(NOW.plusSeconds(60).getEpochSecond()),
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/payment_links/plink_old")).andRespond(withSuccess("""
                {"id":"plink_old","status":"created","amount":64000,"amount_paid":0,"currency":"INR",
                 "expire_by":%d}""".formatted(NOW.minusSeconds(60).getEpochSecond()), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/payment_links/plink_cancelled")).andRespond(withSuccess("""
                {"id":"plink_cancelled","status":"cancelled","amount":64000,"amount_paid":0,"currency":"INR"}""",
                MediaType.APPLICATION_JSON));

        assertThat(gateway.getSession("plink_open").outcome()).isEqualTo(Outcome.OPEN);
        assertThat(gateway.getSession("plink_old").outcome()).isEqualTo(Outcome.EXPIRED);
        assertThat(gateway.getSession("plink_cancelled").outcome()).isEqualTo(Outcome.EXPIRED);
    }

    @Test
    void refundIsSentInPaiseWithOurRefundIdAsReceipt() {
        UUID refundId = UUID.randomUUID();
        server.expect(requestTo(API + "/payments/pay_Xyz/refund")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Refund-Idempotency", refundId.toString()))
                .andExpect(jsonPath("$.amount").value(20000))
                .andExpect(jsonPath("$.speed").value("normal"))
                .andExpect(jsonPath("$.receipt").value(refundId.toString()))
                .andExpect(jsonPath("$.notes.refund_id").value(refundId.toString()))
                .andExpect(jsonPath("$.notes.reason").value("requested_by_customer"))
                .andRespond(withSuccess("""
                        {"id":"rfnd_1","entity":"refund","amount":20000,"payment_id":"pay_Xyz","status":"pending"}""",
                        MediaType.APPLICATION_JSON));

        var result = gateway.refund(new RefundRequest(refundId, "pay_Xyz", new BigDecimal("200.00"),
                "requested_by_customer", null));

        assertThat(result.providerRefundId()).isEqualTo("rfnd_1");
        assertThat(result.outcome()).isEqualTo(Outcome.OPEN); // settles via the refund.processed webhook
        assertThat(RazorpayPaymentGateway.refundOutcome("processed")).isEqualTo(Outcome.SUCCEEDED);
        assertThat(RazorpayPaymentGateway.refundOutcome("failed")).isEqualTo(Outcome.FAILED);
    }

    @Test
    void refundStillInProgressUnderTheSameKeyIsRetryable() {
        server.expect(requestTo(API + "/payments/pay_Xyz/refund")).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"request in progress\"}}"));

        assertThatThrownBy(() -> gateway.refund(new RefundRequest(UUID.randomUUID(), "pay_Xyz",
                new BigDecimal("200.00"), "requested_by_customer", null)))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void cancelsAPaymentLink() {
        server.expect(requestTo(API + "/payment_links/plink_old/cancel")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":"plink_old","status":"cancelled"}""", MediaType.APPLICATION_JSON));

        gateway.cancelSession("plink_old");

        server.verify();
    }

    @Test
    void findsARefundByItsIdOrByOurIdInThePaymentsRefunds() {
        UUID ours = UUID.randomUUID();
        server.expect(requestTo(API + "/refunds/rfnd_1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"rfnd_1","entity":"refund","status":"processed"}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/payments/pay_Xyz/refunds?count=100")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"entity":"collection","count":2,"items":[
                          {"id":"rfnd_other","status":"processed","notes":{"refund_id":"%s"}},
                          {"id":"rfnd_2","status":"failed","notes":{"refund_id":"%s"}}]}"""
                        .formatted(UUID.randomUUID(), ours), MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/payments/pay_None/refunds?count=100"))
                .andRespond(withSuccess("""
                        {"entity":"collection","count":0,"items":[]}""", MediaType.APPLICATION_JSON));

        var byId = gateway.findRefund(ours, "pay_Xyz", "rfnd_1");
        var byNotes = gateway.findRefund(ours, "pay_Xyz", null);

        assertThat(byId.providerRefundId()).isEqualTo("rfnd_1");
        assertThat(byId.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(byNotes.providerRefundId()).isEqualTo("rfnd_2");
        assertThat(byNotes.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(gateway.findRefund(ours, "pay_None", null)).isNull();
        server.verify();
    }

    @Test
    void rejectedRequestIsNotRetryableButAServerErrorIs() {
        server.expect(requestTo(API + "/payment_links/plink_bad")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The id provided does not exist\"}}"));
        server.expect(requestTo(API + "/payment_links/plink_down")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> gateway.getSession("plink_bad"))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.isRetryable()).isFalse());
        assertThatThrownBy(() -> gateway.getSession("plink_down"))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void paiseConversionIsExact() {
        assertThat(RazorpayPaymentGateway.toPaise(new BigDecimal("640.00"))).isEqualTo(64000);
        assertThat(RazorpayPaymentGateway.toPaise(new BigDecimal("0.01"))).isEqualTo(1);
        assertThatThrownBy(() -> RazorpayPaymentGateway.toPaise(new BigDecimal("1.005")))
                .isInstanceOf(ArithmeticException.class);
    }
}
