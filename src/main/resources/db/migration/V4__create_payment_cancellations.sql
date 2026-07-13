CREATE TABLE payment_cancellations (
    id CHAR(36) NOT NULL,
    payment_id CHAR(36) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    state VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cancellations_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT ck_cancellations_amount_positive CHECK (amount_minor_units > 0),
    INDEX ix_cancellations_payment_state (payment_id, state)
) ENGINE = InnoDB;

ALTER TABLE payment_attempts
    ADD CONSTRAINT fk_attempts_cancellation
        FOREIGN KEY (cancellation_id) REFERENCES payment_cancellations (id);

ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_cancellation
        FOREIGN KEY (cancellation_id) REFERENCES payment_cancellations (id);