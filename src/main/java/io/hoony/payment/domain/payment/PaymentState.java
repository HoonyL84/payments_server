package io.hoony.payment.domain.payment;

/**
 * Payment approval lifecycle state.
 */
public enum PaymentState {
    REQUESTED,
    APPROVING,
    PENDING_CONFIRMATION,
    APPROVED,
    FAILED;

    /**
     * Returns whether this state cannot move without an external confirmation.
     */
    public boolean requiresConfirmation() {
        return this == PENDING_CONFIRMATION;
    }

    /**
     * Returns whether this state is final for the approval lifecycle.
     */
    public boolean isTerminal() {
        return this == APPROVED || this == FAILED;
    }
}