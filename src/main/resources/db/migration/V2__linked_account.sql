-- Razorpay Route linked accounts: a business (hospital, ...) that receives its share of payments straight into its
-- own bank account. One per owner user and owner type. Only the last 4 digits of the bank account are kept here;
-- the full number goes to Razorpay and nowhere else.
CREATE TABLE linked_account (
    id                      UUID PRIMARY KEY,
    owner_type              VARCHAR(64)   NOT NULL,
    owner_user_id           VARCHAR(128)  NOT NULL,
    reference_id            VARCHAR(128)  NOT NULL,
    provider                VARCHAR(16)   NOT NULL,
    provider_account_id     VARCHAR(64),
    provider_stakeholder_id VARCHAR(64),
    provider_product_id     VARCHAR(64),
    status                  VARCHAR(32)   NOT NULL,
    requirements            VARCHAR(2000),
    legal_business_name     VARCHAR(200)  NOT NULL,
    email                   VARCHAR(254)  NOT NULL,
    bank_account_last4      VARCHAR(4),
    ifsc_code               VARCHAR(11),
    beneficiary_name        VARCHAR(120),
    onboarding_until        TIMESTAMPTZ,
    created_at              TIMESTAMPTZ   NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,
    activated_at            TIMESTAMPTZ,
    version                 BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_linked_account_owner UNIQUE (owner_type, owner_user_id),
    CONSTRAINT uq_linked_account_provider UNIQUE (provider_account_id),
    CONSTRAINT ck_linked_account_status CHECK (status IN ('PENDING_DETAILS', 'UNDER_REVIEW', 'NEEDS_CLARIFICATION',
                                                          'ACTIVATED', 'SUSPENDED'))
);
CREATE INDEX ix_linked_account_reference ON linked_account (owner_type, reference_id);
CREATE INDEX ix_linked_account_product ON linked_account (provider_product_id);
CREATE INDEX ix_linked_account_pending ON linked_account (updated_at) WHERE status <> 'ACTIVATED';
