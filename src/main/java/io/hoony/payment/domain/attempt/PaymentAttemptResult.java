package io.hoony.payment.domain.attempt;

/**
 * Result returned by a PG attempt or follow-up confirmation.
 */
public enum PaymentAttemptResult {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    UNKNOWN
}