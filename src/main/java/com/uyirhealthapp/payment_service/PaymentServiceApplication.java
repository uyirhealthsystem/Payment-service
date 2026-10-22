package com.uyirhealthapp.payment_service;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentServiceApplication {

	public static void main(String[] args) {
		// Postgres rejects legacy zone names such as Windows' "Asia/Calcutta"; set here so every launcher
		// (IDE, java -jar, mvnw) gets UTC, not only the ones passing -Duser.timezone=UTC
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(PaymentServiceApplication.class, args);
	}

}
