package com.uyirhealthapp.payment_service.gateway;

import java.util.List;

/**
 * Razorpay Route linked accounts, seen from this service: a business (a hospital, ...) that receives its share of a
 * payment straight into its own bank account. Onboarding is four calls, each resumable on its own:
 * account -> stakeholder -> Route product -> bank account. Razorpay then verifies the details and the product
 * moves to ACTIVATED (or NEEDS_CLARIFICATION with a list of what is missing). Implementations:
 * {@link RazorpayLinkedAccountGateway} and {@link MockLinkedAccountGateway}. Failures surface as
 * {@link GatewayException}; a rejected request (bad PAN, IFSC, ...) carries Razorpay's description.
 */
public interface LinkedAccountGateway {

    record Address(String street1, String street2, String city, String state, String postalCode, String country) {
    }

    /** category / subcategory: Razorpay's business category, e.g. healthcare / hospitals. */
    record AccountRequest(String email, String phone, String legalBusinessName, String businessType,
                          String contactName, String category, String subcategory, Address address, String pan,
                          String gst) {
    }

    /** The owner or authorised signatory; pan is their personal PAN. */
    record StakeholderRequest(String name, String email, String phone, String pan) {
    }

    record BankAccount(String accountNumber, String ifscCode, String beneficiaryName) {
    }

    /** Where the Route product stands. Payouts to the account are possible only once ACTIVATED. */
    enum Activation {
        PENDING_DETAILS, UNDER_REVIEW, NEEDS_CLARIFICATION, ACTIVATED, SUSPENDED
    }

    /** requirements: what Razorpay still needs, as "field (reason)", e.g. "settlements.ifsc_code (field_missing)". */
    record ProductState(String productId, Activation activation, List<String> requirements) {
    }

    /** -> account id (acc_...). */
    String createAccount(AccountRequest request);

    /** -> stakeholder id (sth_...). */
    String createStakeholder(String accountId, StakeholderRequest request);

    /** Requests the Route product, accepting its terms. */
    ProductState requestRoute(String accountId);

    /** Sets (or replaces) the bank account the account's share is settled to. */
    ProductState submitBankAccount(String accountId, String productId, BankAccount bank);

    ProductState getRoute(String accountId, String productId);
}
