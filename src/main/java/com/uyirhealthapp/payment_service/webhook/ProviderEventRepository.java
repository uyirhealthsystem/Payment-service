package com.uyirhealthapp.payment_service.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderEventRepository extends JpaRepository<ProviderEvent, String> {
}
