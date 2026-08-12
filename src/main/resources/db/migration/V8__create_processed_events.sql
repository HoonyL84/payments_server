CREATE TABLE processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
) ENGINE = InnoDB;

CREATE TABLE payment_event_effects (
    id BIGINT NOT NULL AUTO_INCREMENT,
    consumer_group VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_event_effect UNIQUE (consumer_group, event_id),
    INDEX ix_payment_event_effect_aggregate (aggregate_id, occurred_at)
) ENGINE = InnoDB;