package io.hoony.payment.infrastructure.fault;

public enum FailurePoint {
    BEFORE_PG,
    AFTER_PG_BEFORE_DB,
    CONFIRMING_WORKER_STOP,
    AFTER_DB_BEFORE_OUTBOX_RELAY,
    AFTER_OUTBOX_PUBLISH_BEFORE_STATUS_UPDATE
}
