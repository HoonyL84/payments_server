package io.hoony.payment.domain.cancellation;

public enum CancellationState {
    CANCEL_REQUESTED,
    CANCELING,
    CANCEL_PENDING_CONFIRMATION,
    CANCEL_CONFIRMING,
    CANCELED,
    CANCEL_FAILED;

    public boolean requiresConfirmation() {
        return this == CANCEL_PENDING_CONFIRMATION;
    }

    public boolean isTerminal() {
        return this == CANCELED || this == CANCEL_FAILED;
    }

    public boolean reservesAmount() {
        return this == CANCELING || this == CANCEL_PENDING_CONFIRMATION || this == CANCEL_CONFIRMING;
    }
}