package io.hoony.payment.domain.outbox;

/**
 * Payment lifecycle event type.
 */
public enum OutboxEventType {
    PAYMENT_APPROVED,
    PAYMENT_FAILED,
    PAYMENT_PENDING_CONFIRMATION,
    PAYMENT_CANCELED,
    PAYMENT_CANCEL_FAILED,
    PAYMENT_CANCEL_PENDING_CONFIRMATION
}