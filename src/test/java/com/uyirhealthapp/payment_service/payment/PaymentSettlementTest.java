package com.uyirhealthapp.payment_service.payment;

import com.uyirhealthapp.payment_service.common.PaymentMetrics;
import com.uyirhealthapp.payment_service.notify.ConsumerNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Mock PaymentRepository payments;
    @Mock CheckoutSessionRepository sessions;
    @Mock ConsumerNotifier notifier;

    private PaymentSettlement settlement;
    private Payment payment;
    private CheckoutSession session;

    @BeforeEach
    void setUp() {
        settlement = new PaymentSettlement(payments, sessions, notifier, new PaymentMetrics(new SimpleMeterRegistry()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setReferenceType("AMBULANCE_BOOKING");
        payment.setReferenceId("b-1");
        payment.setAmount(new BigDecimal("640.00"));
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.PENDING);
        session = new CheckoutSession();
        session.setPaymentId(payment.getId());
        session.setProviderSessionId("sess_1");
        session.setStatus(CheckoutSession.Status.OPEN);
        lenient().when(payments.lockById(payment.getId())).thenReturn(Optional.of(payment));
        lenient().when(sessions.findByProviderSessionId("sess_1")).thenReturn(Optional.of(session));
    }

    @Test
    void paidMarksThePaymentAndTellsTheModule() {
        settlement.paid(payment.getId(), "sess_1", "zp_1", new BigDecimal("640.00"), "INR");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getProviderPaymentId()).isEqualTo("zp_1");
        assertThat(payment.getPaidAt()).isEqualTo(NOW);
        assertThat(session.getStatus()).isEqualTo(CheckoutSession.Status.SUCCEEDED);
        verify(notifier).paymentChanged(payment);
    }

    @Test
    void duplicatePaidIsIgnored() {
        payment.setStatus(PaymentStatus.PAID);
        payment.setProviderPaymentId("zp_1");

        settlement.paid(payment.getId(), "sess_1", "zp_1", new BigDecimal("640.00"), "INR");

        verify(notifier, never()).paymentChanged(any());
    }

    @Test
    void secondPaymentOfAPaidPaymentIsNotReportedAgain() {
        payment.setStatus(PaymentStatus.PAID);
        payment.setProviderPaymentId("zp_1");

        settlement.paid(payment.getId(), "sess_1", "zp_2", new BigDecimal("640.00"), "INR");

        assertThat(payment.getProviderPaymentId()).isEqualTo("zp_1");
        verify(notifier, never()).paymentChanged(any());
    }

    @Test
    void underpaymentOrWrongCurrencyIsNotMarkedPaid() {
        settlement.paid(payment.getId(), "sess_1", "zp_1", new BigDecimal("639.99"), "INR");
        settlement.paid(payment.getId(), "sess_1", "zp_1", new BigDecimal("640.00"), "USD");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(notifier, never()).paymentChanged(any());
    }

    @Test
    void failedMarksThePaymentFailedAndTellsTheModule() {
        when(sessions.findByPaymentIdAndStatus(payment.getId(), CheckoutSession.Status.OPEN)).thenReturn(List.of());

        settlement.failed(payment.getId(), "sess_1", "payment_authentication_failed");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("payment_authentication_failed");
        assertThat(session.getStatus()).isEqualTo(CheckoutSession.Status.FAILED);
        verify(notifier).paymentChanged(payment);
    }

    @Test
    void failureOfAnOldCheckoutIsIgnoredWhileANewerOneIsOpen() {
        CheckoutSession newer = new CheckoutSession();
        newer.setProviderSessionId("sess_2");
        newer.setStatus(CheckoutSession.Status.OPEN);
        when(sessions.findByPaymentIdAndStatus(payment.getId(), CheckoutSession.Status.OPEN))
                .thenReturn(List.of(newer));

        settlement.failed(payment.getId(), "sess_1", "declined");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(notifier, never()).paymentChanged(any());
    }

    @Test
    void failureNeverUndoesAPayment() {
        payment.setStatus(PaymentStatus.PAID);
        session.setStatus(CheckoutSession.Status.SUCCEEDED);

        settlement.failed(payment.getId(), "sess_1", "declined");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(session.getStatus()).isEqualTo(CheckoutSession.Status.SUCCEEDED);
        verify(notifier, never()).paymentChanged(any());
    }

    @Test
    void lateSuccessAfterAFailureStillCounts() {
        payment.setStatus(PaymentStatus.FAILED);
        session.setStatus(CheckoutSession.Status.FAILED);

        settlement.paid(payment.getId(), "sess_1", "zp_1", new BigDecimal("640.00"), "INR");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getFailureReason()).isNull();
        verify(notifier).paymentChanged(payment);
    }
}
