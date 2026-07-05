package io.hoony.payment.domain.idempotency;

/**
 * Idempotency processing state.
 */
public enum IdempotencyStatus {
    PROCESSING,
    COMPLETED,
    FAILED
}