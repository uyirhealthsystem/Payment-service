package com.uyirhealthapp.payment_service.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * LOCAL TESTING ONLY (payment.provider=mock). With auto-pay (default) every checkout is paid the moment it is
 * created, so the modules' whole flow (intent -> signed callback -> booking completed) can be tested without
 * Razorpay credentials. With payment.mock.auto-pay=false checkouts stay open until a (simulated, signed) Razorpay
 * webhook settles them, as in the Postman collection.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "mock")
@Slf4j
public class MockPaymentGateway implements PaymentGateway {

    private final Clock clock;
    private final boolean autoPay;

    public MockPaymentGateway(Clock clock, @Value("${payment.mock.auto-pay:true}") boolean autoPay) {
        this.clock = clock;
        this.autoPay = autoPay;
        log.warn("Using the MOCK payment provider (auto-pay={}): payments are not real. Never enable this in "
                + "production.", autoPay);
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public Session createSession(SessionRequest request) {
        String id = "mock_" + UUID.randomUUID();
        return new Session(id, "https://mock.checkout.invalid/hostedcheckout/" + id,
                clock.instant().plus(Duration.ofMinutes(15)), autoPay ? "mock_pay_" + request.paymentId() : null);
    }

    @Override
    public SessionState getSession(String sessionId) {
        return autoPay
                ? new SessionState(Outcome.SUCCEEDED, "mock_pay_" + sessionId, null, null)
                : new SessionState(Outcome.OPEN, null, null, null);
    }

    @Override
    public void cancelSession(String sessionId) {
        // mock checkouts are not payable anywhere
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        return new RefundResult("mock_refund_" + request.refundId(), Outcome.SUCCEEDED, null);
    }

    @Override
    public RefundResult findRefund(UUID refundId, String providerPaymentId, String providerRefundId) {
        return new RefundResult("mock_refund_" + refundId, Outcome.SUCCEEDED, null);
    }
}
