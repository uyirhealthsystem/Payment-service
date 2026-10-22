package com.uyirhealthapp.payment_service.linkedaccount;

import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.Activation;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A business that is paid its share of payments directly: "HOSPITAL user u-1 (hospital h-9) is Razorpay account
 * acc_...". Owned by the user who onboarded it; referenceId is that module's own id for the business. The provider
 * ids are filled in step by step, so a failed onboarding resumes where it stopped.
 */
@Entity
@Table(name = "linked_account")
@Getter
@Setter
public class LinkedAccount {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String ownerType;

    @Column(nullable = false, updatable = false)
    private String ownerUserId;

    @Column(nullable = false, updatable = false)
    private String referenceId;

    @Column(nullable = false, updatable = false)
    private String provider;

    /** Razorpay: acc_... — where transfers are sent. */
    private String providerAccountId;

    private String providerStakeholderId;

    /** Razorpay: acc_prd_... (the Route product). */
    private String providerProductId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Activation status;

    /** What the provider still needs, comma separated; null when nothing. */
    private String requirements;

    @Column(nullable = false)
    private String legalBusinessName;

    @Column(nullable = false)
    private String email;

    private String bankAccountLast4;

    private String ifscCode;

    private String beneficiaryName;

    /** Set while one request is talking to the provider, so two requests never create two provider accounts. */
    private Instant onboardingUntil;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant activatedAt;

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
