package com.uyirhealthapp.payment_service.linkedaccount;

import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.Activation;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LinkedAccountRepository extends JpaRepository<LinkedAccount, UUID> {

    Optional<LinkedAccount> findByOwnerTypeAndOwnerUserId(String ownerType, String ownerUserId);

    Optional<LinkedAccount> findByProviderProductId(String providerProductId);

    Optional<LinkedAccount> findByProviderAccountId(String providerAccountId);

    List<LinkedAccount> findByStatusInAndProviderProductIdIsNotNullAndUpdatedAtBeforeOrderByUpdatedAt(
            Collection<Activation> statuses, Instant before, Limit limit);

    /** 1 when this request may now talk to the provider for the account, 0 while another request is. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update LinkedAccount a set a.onboardingUntil = :until where a.id = :id "
            + "and (a.onboardingUntil is null or a.onboardingUntil < :now)")
    int claim(UUID id, Instant now, Instant until);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update LinkedAccount a set a.onboardingUntil = null where a.id = :id")
    void release(UUID id);
}
