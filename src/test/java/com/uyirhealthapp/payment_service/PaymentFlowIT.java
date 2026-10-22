package com.uyirhealthapp.payment_service;

import com.sun.net.httpserver.HttpServer;
import com.uyirhealthapp.payment_service.common.Hmac;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.Outcome;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.RefundResult;
import com.uyirhealthapp.payment_service.gateway.PaymentGateway.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end against real Postgres (schema from the Flyway migrations, validated by Hibernate). Razorpay is
 * replaced by a Mockito stub; its webhooks are sent signed exactly as Razorpay signs them. The module side
 * (ambulance) is a tiny HTTP server that checks the callback's signature the way the ambulance service does.
 */
@SpringBootTest(properties = {
        "payment.provider=mock",
        // tokens come from MockMvc's jwt(): the JWKS is never fetched
        "payment.security.jwks-uri=http://jwks.invalid/.well-known/jwks.json",
        "payment.security.gateway-secret=" + PaymentFlowIT.GATEWAY_SECRET,
        "payment.checkout.success-url=https://app.example/payments/result",
        "payment.razorpay.webhook-secret=" + PaymentFlowIT.RAZORPAY_SECRET,
        "payment.consumers[AMBULANCE_BOOKING].webhook-secret=" + PaymentFlowIT.MODULE_SECRET,
        "payment.delivery.poll-interval=200ms",
        "payment.reconcile.enabled=false",
})
@AutoConfigureMockMvc
@Testcontainers
class PaymentFlowIT {

    static final String RAZORPAY_SECRET = "it-razorpay-webhook-secret";
    static final String MODULE_SECRET = "it-module-secret";
    static final String GATEWAY_SECRET = "it-gateway-secret-at-least-32-characters";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    /** What the "ambulance service" received: signature-checked callback bodies. */
    static final BlockingQueue<JsonNode> CALLBACKS = new LinkedBlockingQueue<>();
    static final HttpServer MODULE = startModule();

    @DynamicPropertySource
    static void moduleUrl(DynamicPropertyRegistry registry) {
        registry.add("payment.consumers[AMBULANCE_BOOKING].webhook-url",
                () -> "http://localhost:" + MODULE.getAddress().getPort() + "/api/ambulance/payments/webhook");
    }

    @AfterAll
    static void stopModule() {
        MODULE.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @MockitoBean PaymentGateway gateway;
    private volatile String lastSessionId;

    @BeforeEach
    void setUp() {
        CALLBACKS.clear();
        when(gateway.name()).thenReturn("razorpay");
        when(gateway.createSession(any())).thenAnswer(inv -> razorpaySession());
    }

    // ------------------------------------------------------------------------------------------ tests

    @Test
    void payAndTheModuleGetsASignedCallback() throws Exception {
        String patient = "patient-" + UUID.randomUUID(), booking = UUID.randomUUID().toString();

        JsonNode intent = body(intent(patient, booking, "640.00", "ambulance-booking-" + booking)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(640.00)));
        String paymentId = intent.get("id").asString();
        String checkoutUrl = intent.get("checkoutUrl").asString();
        String linkId = sessionIdOf(paymentId);
        assertThat(checkoutUrl).startsWith("https://rzp.io/i/");

        // retry / double tap: same payment, same checkout
        intent(patient, booking, "640.00", "ambulance-booking-" + booking)
                .andExpect(jsonPath("$.id").value(paymentId))
                .andExpect(jsonPath("$.checkoutUrl").value(checkoutUrl));
        verify(gateway, times(1)).createSession(any());
        // same key, different amount
        intent(patient, booking, "1.00", "ambulance-booking-" + booking).andExpect(status().isConflict());

        // nobody else can see it
        mvc.perform(get("/api/payments/{id}", paymentId).with(user("someone-else", "PATIENT")))
                .andExpect(status().isNotFound());

        // Razorpay reports amounts in paise
        String paid = razorpayPaidEvent(linkId, paymentId, 64000);
        mvc.perform(post("/api/payments/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(paid)
                        .header("X-Razorpay-Signature", "deadbeef").header("X-Razorpay-Event-Id", "evt_1"))
                .andExpect(status().isUnauthorized());
        String eventId = newEventId();
        razorpayWebhook(paid, eventId).andExpect(status().isOk());
        razorpayWebhook(paid, eventId).andExpect(status().isOk()); // Razorpay retries

        mvc.perform(get("/api/payments/{id}", paymentId).with(user(patient, "PATIENT")))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.checkoutUrl").doesNotExist());

        JsonNode callback = CALLBACKS.poll(10, TimeUnit.SECONDS);
        assertThat(callback).as("module callback").isNotNull();
        // exactly the fields the ambulance service's PaymentWebhook record reads
        assertThat(UUID.fromString(callback.get("eventId").asString())).isNotNull();
        assertThat(callback.get("paymentId").asString()).isEqualTo(paymentId);
        assertThat(callback.get("bookingId").asString()).isEqualTo(booking);
        assertThat(callback.get("status").asString()).isEqualTo("PAID");
        assertThat(callback.get("amount").decimalValue()).isEqualByComparingTo("640");
        assertThat(callback.get("referenceId").asString()).isEqualTo(booking);
        assertThat(callback.get("referenceType").asString()).isEqualTo("AMBULANCE_BOOKING");
        assertThat(CALLBACKS.poll(1500, TimeUnit.MILLISECONDS)).as("no second callback for Razorpay's retry").isNull();

        // paying again returns the settled payment, no new checkout
        intent(patient, booking, "640.00", "ambulance-booking-" + booking)
                .andExpect(jsonPath("$.status").value("PAID"));
        verify(gateway, times(1)).createSession(any());
    }

    @Test
    void aPendingRefundSettlesWithTheRefundWebhook() throws Exception {
        String patient = "patient-" + UUID.randomUUID(), booking = UUID.randomUUID().toString();
        String paymentId = paidPayment(patient, booking, "640.00", 64000);

        // a refund Razorpay answers "pending" settles with refund.processed, matched by our id in the notes
        when(gateway.refund(any())).thenReturn(new RefundResult("rfnd_1" + booking.substring(0, 8), Outcome.OPEN, null));
        JsonNode refund = body(mvc.perform(post("/api/payments/{id}/refunds", paymentId).with(user("admin-1", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00,\"reason\":\"OTHERS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING")));
        String processed = """
                {"entity":"event","event":"refund.processed","account_id":"acc_test","contains":["refund","payment"],
                 "payload":{"refund":{"entity":{"id":"rfnd_1%s","entity":"refund","amount":10000,"payment_id":"pay_1",
                 "status":"processed","notes":{"refund_id":"%s"}}}}}""".formatted(booking.substring(0, 8),
                refund.get("id").asString());
        razorpayWebhook(processed, "evt_r" + booking.substring(0, 8)).andExpect(status().isOk());
        mvc.perform(get("/api/payments/{id}", paymentId).with(user(patient, "PATIENT")))
                .andExpect(jsonPath("$.refundedAmount").value(100.00))
                .andExpect(jsonPath("$.refunds[0].status").value("SUCCEEDED"));
    }

    @Test
    void underpaidRazorpayLinkIsNotMarkedPaid() throws Exception {
        String patient = "patient-" + UUID.randomUUID(), booking = UUID.randomUUID().toString();
        String paymentId = body(intent(patient, booking, "640.00", "ambulance-booking-" + booking)).get("id").asString();

        razorpayWebhook(razorpayPaidEvent(sessionIdOf(paymentId), paymentId, 100), newEventId())
                .andExpect(status().isOk());

        mvc.perform(get("/api/payments/{id}", paymentId).with(user(patient, "PATIENT")))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void refundsAreForAdminsAndCannotExceedThePayment() throws Exception {
        String patient = "patient-" + UUID.randomUUID(), booking = UUID.randomUUID().toString();
        String paymentId = paidPayment(patient, booking, "500.00", 50000);
        when(gateway.refund(any())).thenReturn(new RefundResult("rfnd_r1", Outcome.SUCCEEDED, null));

        String partial = "{\"amount\":200.00,\"reason\":\"REQUESTED_BY_CUSTOMER\"}";
        mvc.perform(post("/api/payments/{id}/refunds", paymentId).with(user(patient, "PATIENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(partial))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/payments/{id}/refunds", paymentId).with(user("admin-1", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(partial))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(post("/api/payments/{id}/refunds", paymentId).with(user("admin-1", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(
                                "{\"amount\":300.01,\"reason\":\"REQUESTED_BY_CUSTOMER\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/payments/{id}", paymentId).with(user(patient, "PATIENT")))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.refundedAmount").value(200.00))
                .andExpect(jsonPath("$.refunds[0].status").value("SUCCEEDED"));
    }

    @Test
    void afterAnExpiredLinkTheUserGetsAFreshCheckout() throws Exception {
        String patient = "patient-" + UUID.randomUUID(), booking = UUID.randomUUID().toString();
        String key = "ambulance-booking-" + booking;
        JsonNode first = body(intent(patient, booking, "300.00", key));
        String paymentId = first.get("id").asString();
        String url = first.get("checkoutUrl").asString();

        String expired = """
                {"entity":"event","event":"payment_link.expired","account_id":"acc_test","contains":["payment_link"],
                 "payload":{"payment_link":{"entity":{"id":"%s","amount":30000,"amount_paid":0,"currency":"INR",
                 "status":"expired","notes":{"payment_id":"%s"}}}}}""".formatted(sessionIdOf(paymentId), paymentId);
        razorpayWebhook(expired, newEventId()).andExpect(status().isOk());
        // still owed: no checkout to show, and nothing to tell the module
        mvc.perform(get("/api/payments/{id}", paymentId).with(user(patient, "PATIENT")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.checkoutUrl").doesNotExist());
        assertThat(CALLBACKS.poll(1500, TimeUnit.MILLISECONDS)).isNull();

        JsonNode retry = body(intent(patient, booking, "300.00", key).andExpect(status().isOk()));
        assertThat(retry.get("id").asString()).isEqualTo(paymentId);
        assertThat(retry.get("status").asString()).isEqualTo("PENDING");
        assertThat(retry.get("checkoutUrl").asString()).isNotEqualTo(url);
    }

    @Test
    void rejectsBadIntents() throws Exception {
        String patient = "patient-" + UUID.randomUUID();
        // missing Idempotency-Key
        mvc.perform(post("/api/payments/intents").with(user(patient, "PATIENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(intentBody("UNKNOWN_THING", "b", patient, "10.00")
                                .replace("UNKNOWN_THING", "AMBULANCE_BOOKING")))
                .andExpect(status().isBadRequest());
        // module not registered
        mvc.perform(post("/api/payments/intents").with(user(patient, "PATIENT")).header("Idempotency-Key", "key-00001")
                        .contentType(MediaType.APPLICATION_JSON).content(intentBody("UNKNOWN_THING", "b", patient, "10.00")))
                .andExpect(status().isBadRequest());
        // paying on someone else's behalf
        mvc.perform(post("/api/payments/intents").with(user(patient, "PATIENT")).header("Idempotency-Key", "key-00002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(intentBody("AMBULANCE_BOOKING", "b", "another-patient", "10.00")))
                .andExpect(status().isForbidden());
        // below the minimum, and more than 2 decimals
        mvc.perform(post("/api/payments/intents").with(user(patient, "PATIENT")).header("Idempotency-Key", "key-00003")
                        .contentType(MediaType.APPLICATION_JSON).content(intentBody("AMBULANCE_BOOKING", "b", patient, "0.50")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/payments/intents").with(user(patient, "PATIENT")).header("Idempotency-Key", "key-00004")
                        .contentType(MediaType.APPLICATION_JSON).content(intentBody("AMBULANCE_BOOKING", "b", patient, "10.001")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/payments/intents").contentType(MediaType.APPLICATION_JSON)
                        .content(intentBody("AMBULANCE_BOOKING", "b", patient, "10.00")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void identityFromTheGatewayIsTrustedOnlyWithItsSecret() throws Exception {
        String patient = "patient-" + UUID.randomUUID();
        String body = intentBody("AMBULANCE_BOOKING", UUID.randomUUID().toString(), patient, "120.00");

        // as the api-gateway sends it: verified identity + secret, plus the original (unverifiable here) token
        JsonNode created = body(mvc.perform(post("/api/payments/intents")
                        .header("X-Gateway-Secret", GATEWAY_SECRET)
                        .header("X-User-Id", patient).header("X-User-Role", "PATIENT")
                        .header("Authorization", "Bearer not-checked-here")
                        .header("Idempotency-Key", "gw-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()));
        String id = created.get("id").asString();
        mvc.perform(get("/api/payments/" + id)
                        .header("X-Gateway-Secret", GATEWAY_SECRET).header("X-User-Id", patient)
                        .header("X-User-Role", "PATIENT"))
                .andExpect(status().isOk());

        // headers without the secret, or with a wrong one, prove nothing
        mvc.perform(get("/api/payments/" + id).header("X-User-Id", patient).header("X-User-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/" + id).header("X-Gateway-Secret", "wrong-secret-wrong-secret-wrong-secret")
                        .header("X-User-Id", patient).header("X-User-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        // another gateway-verified user cannot see it
        mvc.perform(get("/api/payments/" + id)
                        .header("X-Gateway-Secret", GATEWAY_SECRET).header("X-User-Id", "someone-else")
                        .header("X-User-Role", "PATIENT"))
                .andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------------------------------ helpers

    private ResultActions intent(String patient, String booking, String amount, String key) throws Exception {
        return mvc.perform(post("/api/payments/intents").with(user(patient, "PATIENT"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(intentBody("AMBULANCE_BOOKING", booking, patient, amount)));
    }

    private static String intentBody(String type, String ref, String payer, String amount) {
        return """
                {"referenceType":"%s","referenceId":"%s","payerId":"%s","amount":%s,"currency":"INR"}"""
                .formatted(type, ref, payer, amount);
    }

    /** Creates a payment and settles it with a signed payment_link.paid; returns its id. */
    private String paidPayment(String patient, String booking, String amount, long paise) throws Exception {
        String paymentId = body(intent(patient, booking, amount, "ambulance-booking-" + booking)).get("id").asString();
        razorpayWebhook(razorpayPaidEvent(sessionIdOf(paymentId), paymentId, paise), newEventId())
                .andExpect(status().isOk());
        assertThat(CALLBACKS.poll(10, TimeUnit.SECONDS)).as("PAID callback").isNotNull();
        return paymentId;
    }

    private static String newEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    /** payment_link.paid as Razorpay sends it (amounts in paise, our payment id in the link's notes). */
    private static String razorpayPaidEvent(String linkId, String paymentId, long paise) {
        return """
                {"entity":"event","event":"payment_link.paid","account_id":"acc_test",
                 "contains":["payment_link","order","payment"],"created_at":1791458400,"payload":{
                  "payment_link":{"entity":{"id":"%s","amount":%d,"amount_paid":%d,"currency":"INR","status":"paid",
                                            "notes":{"payment_id":"%s","reference_type":"AMBULANCE_BOOKING"}}},
                  "order":{"entity":{"id":"order_1","amount":%d,"status":"paid"}},
                  "payment":{"entity":{"id":"pay_%s","amount":%d,"currency":"INR","status":"captured","method":"upi"}}}}"""
                .formatted(linkId, paise, paise, paymentId, paise, linkId.substring(6), paise);
    }

    /** Signed the way Razorpay signs: X-Razorpay-Signature = hex(HMAC-SHA256(secret, raw body)). */
    private ResultActions razorpayWebhook(String body, String eventId) throws Exception {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post("/api/payments/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).content(raw)
                .header("X-Razorpay-Signature", Hmac.sign(RAZORPAY_SECRET, raw))
                .header("X-Razorpay-Event-Id", eventId));
    }

    /** A Razorpay-like checkout whose id (plink_...) is not part of its URL, as with the real short_url. */
    private Session razorpaySession() {
        lastSessionId = "plink_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        return new Session(lastSessionId, "https://rzp.io/i/" + UUID.randomUUID(),
                Instant.now().plus(Duration.ofMinutes(20)), null);
    }

    /** The link id of the payment's checkout, i.e. the last one the stub created for it. */
    private String sessionIdOf(String paymentId) {
        var request = org.mockito.ArgumentCaptor.forClass(PaymentGateway.SessionRequest.class);
        verify(gateway, org.mockito.Mockito.atLeastOnce()).createSession(request.capture());
        assertThat(request.getValue().paymentId().toString()).isEqualTo(paymentId);
        return lastSessionId;
    }

    private static RequestPostProcessor user(String id, String role) {
        return jwt().jwt(j -> j.subject(id).claim("role", role))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Checks X-Payment-Timestamp / X-Payment-Signature exactly like ambulance's WebhookSignature, else 401. */
    private static HttpServer startModule() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            JsonMapper mapper = JsonMapper.builder().build();
            server.createContext("/api/ambulance/payments/webhook", exchange -> {
                byte[] raw = exchange.getRequestBody().readAllBytes();
                String ts = exchange.getRequestHeaders().getFirst("X-Payment-Timestamp");
                String sig = exchange.getRequestHeaders().getFirst("X-Payment-Signature");
                boolean fresh = ts != null && Math.abs(Instant.now().getEpochSecond() - Long.parseLong(ts)) < 300;
                boolean ok = fresh && Hmac.matches(Hmac.sign(MODULE_SECRET, ts, raw), sig);
                if (ok) {
                    CALLBACKS.add(mapper.readTree(raw));
                }
                byte[] response = mapper.writeValueAsBytes(Map.of("ok", ok));
                exchange.sendResponseHeaders(ok ? 200 : 401, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
