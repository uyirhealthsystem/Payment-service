package com.uyirhealthapp.payment_service.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Something a user owes a module: "AMBULANCE_BOOKING 7f3c... costs 640.00 INR". The module owns the amount and
 * the booking; this service owns the money. One payment can take several checkout attempts
 * ({@link CheckoutSession}) until one succeeds.
 */
@Entity
@Table(name = "payment")
@Getter
@Setter
public class Payment {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private String referenceType;

    @Column(nullable = false, updatable = false)
    private String referenceId;

    @Column(nullable = false, updatable = false)
    private String payerId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false, updatable = false)
    private String provider;

    /** The provider's id of the successful payment (Razorpay: pay_...). */
    private String providerPaymentId;

    private String failureReason;

    @Column(nullable = false)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant paidAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
