package io.hoony.payment.domain.payment;

public enum PaymentEvent {
    APPROVE_STARTED,
    APPROVE_SUCCEEDED,
    APPROVE_FAILED,
    APPROVE_TIMED_OUT,
    CONFIRM_STARTED,
    CONFIRM_APPROVED,
    CONFIRM_FAILED,
    CONFIRM_UNKNOWN
}
