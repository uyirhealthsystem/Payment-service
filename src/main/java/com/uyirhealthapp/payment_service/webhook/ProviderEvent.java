package com.uyirhealthapp.payment_service.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A provider webhook event already applied; the provider retries deliveries, so each event_id is processed once. */
@Entity
@Table(name = "provider_event")
@Getter
@Setter
@NoArgsConstructor
public class ProviderEvent {

    @Id
    private String eventId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private Instant receivedAt;

    public ProviderEvent(String eventId, String eventType, Instant receivedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.receivedAt = receivedAt;
    }
}
