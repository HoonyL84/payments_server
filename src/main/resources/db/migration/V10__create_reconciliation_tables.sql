CREATE TABLE reconciliation_pg_snapshots (
    run_key VARCHAR(100) NOT NULL,
    provider_request_id VARCHAR(128) NOT NULL,
    payment_id CHAR(36) NOT NULL,
    cancellation_id CHAR(36) NULL,
    operation VARCHAR(20) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_transaction_id VARCHAR(128) NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (run_key, provider_request_id),
    INDEX ix_reconciliation_pg_payment (run_key, payment_id, operation, occurred_at)
) ENGINE = InnoDB;

CREATE TABLE reconciliation_cases (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_key VARCHAR(100) NOT NULL,
    payment_id CHAR(36) NOT NULL,
    case_type VARCHAR(64) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    expected_value VARCHAR(255) NULL,
    actual_value VARCHAR(255) NULL,
    detail VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reconciliation_case UNIQUE (run_key, payment_id, case_type),
    INDEX ix_reconciliation_case_classification (run_key, classification)
) ENGINE = InnoDB;

CREATE TABLE reconciliation_corrections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    correction_key VARCHAR(200) NOT NULL,
    payment_id CHAR(36) NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    source_run_key VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reconciliation_correction UNIQUE (correction_key),
    INDEX ix_reconciliation_correction_status (status, requested_at)
) ENGINE = InnoDB;

CREATE TABLE reconciliation_failure_markers (
    run_key VARCHAR(100) NOT NULL,
    failure_point VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (run_key, failure_point)
) ENGINE = InnoDB;

CREATE TABLE reconciliation_run_summaries (
    run_key VARCHAR(100) NOT NULL,
    job_execution_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    read_count BIGINT NOT NULL,
    write_count BIGINT NOT NULL,
    auto_correct_count BIGINT NOT NULL,
    requery_count BIGINT NOT NULL,
    manual_review_count BIGINT NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NOT NULL,
    duration_millis BIGINT NOT NULL,
    PRIMARY KEY (run_key, job_execution_id)
) ENGINE = InnoDB;

CREATE INDEX ix_outbox_aggregate_event
    ON outbox_events(aggregate_id, event_type, status);