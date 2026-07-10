package io.hoony.payment.domain.payment;

public enum PaymentState {
    REQUESTED,
    APPROVING,
    PENDING_CONFIRMATION,
    CONFIRMING,
    APPROVED,
    FAILED;

    public boolean requiresConfirmation() {
        return this == PENDING_CONFIRMATION;
    }

    public boolean isTerminal() {
        return this == APPROVED || this == FAILED;
    }
}
