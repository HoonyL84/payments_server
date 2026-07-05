package io.hoony.payment.domain.attempt;

/**
 * PG operation type.
 */
public enum PaymentOperation {
    APPROVE,
    CANCEL,
    CONFIRM_APPROVE,
    CONFIRM_CANCEL
}