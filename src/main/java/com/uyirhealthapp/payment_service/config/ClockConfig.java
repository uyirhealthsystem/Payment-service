package com.uyirhealthapp.payment_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** A Clock bean so time-dependent logic (webhook expiry, checkout expiry) can be tested with a fixed clock. */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
