package com.uyirhealthapp.payment_service.linkedaccount;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * "payment.linked-accounts.*". ownerRoles: which owner types may onboard a linked account, and the role the caller
 * must have for it, e.g. owner-roles[HOSPITAL]=HOSPITAL. Any other ownerType is refused.
 * claimTimeout: how long one onboarding request holds the account (a crashed request frees it after this).
 * refreshAfter / refreshInterval: accounts still in review are looked up at the provider this long after their last
 * change, every refreshInterval, in case a webhook was lost.
 */
@ConfigurationProperties(prefix = "payment.linked-accounts")
@Validated
public record LinkedAccountProperties(Map<String, String> ownerRoles,
                                      @DefaultValue("2m") @NotNull Duration claimTimeout,
                                      @DefaultValue("10m") @NotNull Duration refreshAfter,
                                      @DefaultValue("10m") @NotNull Duration refreshInterval) {

    public LinkedAccountProperties {
        ownerRoles = ownerRoles == null ? Map.of() : Map.copyOf(ownerRoles);
    }

    /** The role a caller needs to onboard an ownerType, or null when the ownerType is not accepted. */
    public String roleFor(String ownerType) {
        return ownerType == null ? null : ownerRoles.get(ownerType);
    }
}
