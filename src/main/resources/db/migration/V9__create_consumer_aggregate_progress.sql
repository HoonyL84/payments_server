CREATE TABLE consumer_aggregate_progress (
    consumer_group VARCHAR(100) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    last_occurred_at TIMESTAMP(6) NOT NULL,
    last_event_id CHAR(36) NOT NULL,
    PRIMARY KEY (consumer_group, aggregate_id)
) ENGINE = InnoDB;