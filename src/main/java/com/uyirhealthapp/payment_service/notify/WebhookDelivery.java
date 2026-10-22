package com.uyirhealthapp.payment_service.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Outbox row: one result callback to a module, retried until it answers 2xx or maxAttempts is reached. */
@Entity
@Table(name = "webhook_delivery")
@Getter
@Setter
public class WebhookDelivery {

    public enum Status {
        PENDING, DELIVERED, DEAD
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID paymentId;

    @Column(nullable = false, updatable = false)
    private String referenceType;

    @Column(nullable = false, updatable = false)
    private String url;

    /** Exact JSON sent (and signed); built when the event happened, so it describes that moment. */
    @Column(nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant deliveredAt;
}
