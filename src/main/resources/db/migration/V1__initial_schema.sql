-- One row per thing someone owes: "booking X of module Y costs Z". The Idempotency-Key the module sends makes
-- retries and double taps land on the same row.
CREATE TABLE payment (
    id                  UUID PRIMARY KEY,
    idempotency_key     VARCHAR(128)   NOT NULL,
    reference_type      VARCHAR(64)    NOT NULL,
    reference_id        VARCHAR(128)   NOT NULL,
    payer_id            VARCHAR(128)   NOT NULL,
    amount              NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)     NOT NULL,
    description         VARCHAR(500)   NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    provider            VARCHAR(16)    NOT NULL,
    provider_payment_id VARCHAR(64),
    failure_reason      VARCHAR(255),
    refunded_amount     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL,
    updated_at          TIMESTAMPTZ    NOT NULL,
    paid_at             TIMESTAMPTZ,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'PAID', 'FAILED'))
);
CREATE INDEX ix_payment_reference ON payment (reference_type, reference_id);
CREATE INDEX ix_payment_payer ON payment (payer_id, created_at DESC);

-- One Zoho payment session (a hosted checkout page, valid for at most 15 minutes) per attempt to pay.
CREATE TABLE checkout_session (
    id                  UUID PRIMARY KEY,
    payment_id          UUID          NOT NULL REFERENCES payment (id),
    provider_session_id VARCHAR(64)   NOT NULL,
    checkout_url        VARCHAR(1024),
    status              VARCHAR(16)   NOT NULL,
    expires_at          TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_checkout_session_provider UNIQUE (provider_session_id),
    CONSTRAINT ck_checkout_session_status CHECK (status IN ('OPEN', 'SUCCEEDED', 'FAILED', 'EXPIRED'))
);
CREATE INDEX ix_checkout_session_payment ON checkout_session (payment_id, created_at DESC);
CREATE INDEX ix_checkout_session_open ON checkout_session (created_at) WHERE status = 'OPEN';

CREATE TABLE refund (
    id                 UUID PRIMARY KEY,
    payment_id         UUID           NOT NULL REFERENCES payment (id),
    provider_refund_id VARCHAR(64),
    amount             NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    reason             VARCHAR(32)    NOT NULL,
    note               VARCHAR(500),
    status             VARCHAR(16)    NOT NULL,
    failure_reason     VARCHAR(255),
    requested_by       VARCHAR(128)   NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL,
    updated_at         TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_refund_provider UNIQUE (provider_refund_id),
    CONSTRAINT ck_refund_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);
CREATE INDEX ix_refund_payment ON refund (payment_id);

-- Zoho webhook event ids already processed: Zoho retries deliveries, each event is applied once.
CREATE TABLE provider_event (
    event_id    VARCHAR(64) PRIMARY KEY,
    event_type  VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL
);

-- Outbox of result callbacks to the modules (ambulance, appointments, ...). Written in the same transaction
-- that settles the payment, delivered by WebhookDispatcher with retries.
CREATE TABLE webhook_delivery (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      UUID          NOT NULL REFERENCES payment (id),
    reference_type  VARCHAR(64)   NOT NULL,
    url             VARCHAR(1024) NOT NULL,
    payload         TEXT          NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    attempts        INT           NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ   NOT NULL,
    delivered_at    TIMESTAMPTZ,
    CONSTRAINT ck_webhook_delivery_status CHECK (status IN ('PENDING', 'DELIVERED', 'DEAD'))
);
CREATE INDEX ix_webhook_delivery_due ON webhook_delivery (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_webhook_delivery_payment ON webhook_delivery (payment_id);
