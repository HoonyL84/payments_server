package io.hoony.payment.domain.cancellation;

public enum CancellationEvent {
    CANCEL_STARTED,
    CANCEL_SUCCEEDED,
    CANCEL_FAILED,
    CANCEL_TIMED_OUT,
    CONFIRM_CANCEL_STARTED,
    CONFIRM_CANCELED,
    CONFIRM_CANCEL_FAILED,
    CONFIRM_CANCEL_UNKNOWN
}