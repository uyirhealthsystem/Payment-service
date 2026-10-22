package com.uyirhealthapp.payment_service.refund;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByProviderRefundId(String providerRefundId);

    List<Refund> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.paymentId = :paymentId and r.status in :statuses")
    BigDecimal sumAmount(UUID paymentId, Collection<Refund.Status> statuses);

    /** Refunds in a status, least recently changed (or checked) first. */
    List<Refund> findByStatusAndUpdatedAtBeforeOrderByUpdatedAt(Refund.Status status, Instant before, Limit limit);

    /** Marks a refund as just checked, so the next reconciliation round looks at the others first. */
    @Transactional
    @Modifying
    @Query("update Refund r set r.updatedAt = :at where r.id = :id")
    void touch(UUID id, Instant at);
}
