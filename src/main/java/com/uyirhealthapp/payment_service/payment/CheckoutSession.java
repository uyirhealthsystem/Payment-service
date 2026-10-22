package com.uyirhealthapp.payment_service.payment;

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

import java.time.Instant;
import java.util.UUID;

/** One attempt to pay: a Razorpay payment link, i.e. a hosted checkout page with an expiry. */
@Entity
@Table(name = "checkout_session")
@Getter
@Setter
public class CheckoutSession {

    public enum Status {
        OPEN, SUCCEEDED, FAILED, EXPIRED
    }

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID paymentId;

    @Column(nullable = false, updatable = false)
    private String providerSessionId;

    private String checkoutUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private Instant expiresAt;

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

    /** Still worth sending the user to: open and not about to expire. */
    public boolean isUsableAt(Instant at) {
        return status == Status.OPEN && expiresAt.isAfter(at);
    }
}
