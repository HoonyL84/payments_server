CREATE TABLE pg_webhook_receipts (
    event_id CHAR(36) NOT NULL,
    provider_request_id VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    claimed_until TIMESTAMP(6) NULL,
    received_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (event_id),
    INDEX ix_pg_webhook_receipts_claim (status, claimed_until)
) ENGINE = InnoDB;