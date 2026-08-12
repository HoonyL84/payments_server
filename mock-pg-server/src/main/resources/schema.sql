CREATE TABLE IF NOT EXISTS pg_transactions (
    provider_request_id VARCHAR(128) PRIMARY KEY,
    operation VARCHAR(20) NOT NULL,
    payment_id CHAR(36) NOT NULL,
    cancellation_id CHAR(36),
    merchant_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(128),
    amount_minor_units BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    original_provider_transaction_id VARCHAR(128),
    fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_transaction_id VARCHAR(128),
    error_code VARCHAR(64),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_pg_transactions_created
    ON pg_transactions(created_at, provider_request_id);