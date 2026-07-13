ALTER TABLE idempotency_records
    MODIFY COLUMN fingerprint VARCHAR(64) NOT NULL;
