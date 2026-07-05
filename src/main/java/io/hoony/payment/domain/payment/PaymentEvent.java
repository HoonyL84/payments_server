package io.hoony.payment.domain.payment;

/**
 * Event that moves a payment between lifecycle states.
 */
public enum PaymentEvent {
    APPROVE_STARTED,
    APPROVE_SUCCEEDED,
    APPROVE_FAILED,
    APPROVE_TIMED_OUT,
    CONFIRM_APPROVED,
    CONFIRM_FAILED
}