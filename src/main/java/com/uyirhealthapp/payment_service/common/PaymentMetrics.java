package com.uyirhealthapp.payment_service.common;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business counters for /actuator/prometheus. The "anomaly" counters (amount mismatch, double payment,
 * dead callbacks) should be alerted on: each one needs a human.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void intentCreated(String referenceType) {
        registry.counter("payments.intents.created", "reference_type", referenceType).increment();
    }

    public void paid(String referenceType) {
        registry.counter("payments.paid", "reference_type", referenceType).increment();
    }

    public void failed(String referenceType) {
        registry.counter("payments.failed", "reference_type", referenceType).increment();
    }

    public void refunded(String status) {
        registry.counter("payments.refunds", "status", status).increment();
    }

    public void anomaly(String kind) {
        registry.counter("payments.anomalies", "kind", kind).increment();
    }

    public void callback(String referenceType, String outcome) {
        registry.counter("payments.callbacks", "reference_type", referenceType, "outcome", outcome).increment();
    }
}
