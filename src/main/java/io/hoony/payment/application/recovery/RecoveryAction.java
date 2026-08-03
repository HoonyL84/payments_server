package io.hoony.payment.application.recovery;

public enum RecoveryAction {
    CONFIRM_PAYMENT,
    CONFIRM_CANCELLATION,
    RELAY_OUTBOX,
    MANUAL_REVIEW
}
