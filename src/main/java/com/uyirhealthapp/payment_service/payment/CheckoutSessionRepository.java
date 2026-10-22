package com.uyirhealthapp.payment_service.payment;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, UUID> {

    Optional<CheckoutSession> findByProviderSessionId(String providerSessionId);

    Optional<CheckoutSession> findFirstByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    List<CheckoutSession> findByPaymentIdAndStatus(UUID paymentId, CheckoutSession.Status status);

    /** Open checkouts nobody has told us about for a while (reconciliation). */
    List<CheckoutSession> findByStatusAndCreatedAtBeforeOrderByCreatedAt(CheckoutSession.Status status,
                                                                          Instant before, Limit limit);
}
