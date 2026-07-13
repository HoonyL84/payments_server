ALTER TABLE outbox_events
    ADD COLUMN claim_owner VARCHAR(100) NULL AFTER publish_attempts,
    ADD COLUMN claimed_until TIMESTAMP(6) NULL AFTER claim_owner,
    ADD INDEX ix_outbox_claimable (status, claimed_until, created_at);
