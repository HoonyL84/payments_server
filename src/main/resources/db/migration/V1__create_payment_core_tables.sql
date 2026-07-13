CREATE TABLE merchants (
    id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    pg_provider VARCHAR(32) NOT NULL,
    routing_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE payments (
    id CHAR(36) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(128) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    state VARCHAR(32) NOT NULL,
    canceled_amount_minor_units BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_payments_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT uk_payments_merchant_order UNIQUE (merchant_id, order_id),
    CONSTRAINT ck_payments_amount_positive CHECK (amount_minor_units > 0),
    CONSTRAINT ck_payments_canceled_amount CHECK (
        canceled_amount_minor_units >= 0 AND canceled_amount_minor_units <= amount_minor_units
    )
) ENGINE = InnoDB;

CREATE TABLE idempotency_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    merchant_id VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_body TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_idempotency_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT uk_idempotency_scope UNIQUE (merchant_id, operation, idempotency_key)
) ENGINE = InnoDB;

CREATE TABLE payment_attempts (
    id CHAR(36) NOT NULL,
    payment_id CHAR(36) NOT NULL,
    cancellation_id CHAR(36) NULL,
    operation VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_request_id VARCHAR(128) NOT NULL,
    provider_transaction_id VARCHAR(128) NULL,
    result VARCHAR(20) NOT NULL,
    error_code VARCHAR(64) NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_attempts_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT uk_attempts_provider_request UNIQUE (provider, provider_request_id),
    INDEX ix_attempts_payment_started (payment_id, started_at)
) ENGINE = InnoDB;

CREATE TABLE ledger_entries (
    id CHAR(36) NOT NULL,
    transaction_group_id CHAR(36) NOT NULL,
    payment_id CHAR(36) NOT NULL,
    cancellation_id CHAR(36) NULL,
    entry_type VARCHAR(20) NOT NULL,
    account VARCHAR(32) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ledger_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT uk_ledger_group_account_direction UNIQUE (transaction_group_id, account, direction),
    CONSTRAINT ck_ledger_amount_positive CHECK (amount_minor_units > 0),
    INDEX ix_ledger_payment (payment_id)
) ENGINE = InnoDB;

CREATE TABLE outbox_events (
    id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    publish_attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    INDEX ix_outbox_status_created (status, created_at)
) ENGINE = InnoDB;
