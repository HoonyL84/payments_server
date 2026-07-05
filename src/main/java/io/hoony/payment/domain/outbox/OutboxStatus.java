package io.hoony.payment.domain.outbox;

/**
 * Outbox publication status.
 */
public enum OutboxStatus {
    PENDING,
    PUBLISHED,
    FAILED
}