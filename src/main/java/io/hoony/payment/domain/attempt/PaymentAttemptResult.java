package io.hoony.payment.domain.attempt;

public enum PaymentAttemptResult {
    PROCESSING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    UNKNOWN
}
