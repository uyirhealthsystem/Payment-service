package com.uyirhealthapp.payment_service.refund;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A (partial) refund of a PAID payment. Written as PENDING before the provider is called, so concurrent refunds cannot
 * together exceed what was paid.
 */
@Entity
@Table(name = "refund")
@Getter
@Setter
public class Refund {

    public enum Status {
        PENDING, SUCCEEDED, FAILED
    }

    /** Why the money goes back; code is sent to the provider (Razorpay: notes.reason). */
    public enum Reason {
        DUPLICATE("duplicate"),
        FRAUDULENT("fraudulent"),
        REQUESTED_BY_CUSTOMER("requested_by_customer"),
        OTHERS("others");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID paymentId;

    private String providerRefundId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Reason reason;

    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String failureReason;

    @Column(nullable = false, updatable = false)
    private String requestedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
