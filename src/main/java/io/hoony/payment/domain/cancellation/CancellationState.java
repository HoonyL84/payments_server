package io.hoony.payment.domain.cancellation;

/**
 * Payment cancellation lifecycle state.
 */
public enum CancellationState {
    CANCEL_REQUESTED,
    CANCELING,
    CANCEL_PENDING_CONFIRMATION,
    CANCELED,
    CANCEL_FAILED;

    /**
     * Returns whether this cancellation waits for PG confirmation.
     */
    public boolean requiresConfirmation() {
        return this == CANCEL_PENDING_CONFIRMATION;
    }

    /**
     * Returns whether this cancellation lifecycle is final.
     */
    public boolean isTerminal() {
        return this == CANCELED || this == CANCEL_FAILED;
    }
}