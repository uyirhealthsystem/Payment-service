package com.uyirhealthapp.payment_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs: callback delivery and reconciliation. They are safe with several instances running without
 * a lock service: deliveries are claimed with SELECT ... FOR UPDATE SKIP LOCKED, and settling a payment twice
 * is a no-op.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
