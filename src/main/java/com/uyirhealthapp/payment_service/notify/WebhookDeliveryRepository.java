package com.uyirhealthapp.payment_service.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    /**
     * Due deliveries, locked so that other instances skip them (they take the next ones instead).
     * Must run inside a transaction; the caller pushes next_attempt_at forward as a lease before committing.
     */
    @Query(value = """
            SELECT * FROM webhook_delivery
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<WebhookDelivery> lockDue(Instant now, int limit);
}
