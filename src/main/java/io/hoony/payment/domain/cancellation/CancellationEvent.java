package io.hoony.payment.domain.cancellation;

/**
 * Event that moves a cancellation between lifecycle states.
 */
public enum CancellationEvent {
    CANCEL_STARTED,
    CANCEL_SUCCEEDED,
    CANCEL_FAILED,
    CANCEL_TIMED_OUT,
    CONFIRM_CANCELED,
    CONFIRM_CANCEL_FAILED
}