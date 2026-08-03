CREATE INDEX ix_payments_state_updated
    ON payments (state, updated_at);

CREATE INDEX ix_cancellations_state_updated
    ON payment_cancellations (state, updated_at);
