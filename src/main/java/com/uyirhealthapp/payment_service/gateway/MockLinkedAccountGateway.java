package com.uyirhealthapp.payment_service.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * LOCAL TESTING ONLY (payment.provider=mock): every linked account is ACTIVATED as soon as its bank account is
 * submitted, without Razorpay credentials or KYC.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "mock")
public class MockLinkedAccountGateway implements LinkedAccountGateway {

    @Override
    public String createAccount(AccountRequest request) {
        return "acc_mock" + shortId();
    }

    @Override
    public String createStakeholder(String accountId, StakeholderRequest request) {
        return "sth_mock" + shortId();
    }

    @Override
    public ProductState requestRoute(String accountId) {
        return new ProductState("acc_prd_mock" + shortId(), Activation.PENDING_DETAILS,
                List.of("settlements.account_number (field_missing)"));
    }

    @Override
    public ProductState submitBankAccount(String accountId, String productId, BankAccount bank) {
        return new ProductState(productId, Activation.ACTIVATED, List.of());
    }

    @Override
    public ProductState getRoute(String accountId, String productId) {
        return new ProductState(productId, Activation.ACTIVATED, List.of());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }
}
