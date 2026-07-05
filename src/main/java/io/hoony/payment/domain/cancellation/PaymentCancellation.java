package io.hoony.payment.domain.cancellation;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.money.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * Cancellation aggregate that owns cancel amount and lifecycle state.
 */
public class PaymentCancellation {

    private final UUID id;
    private final UUID paymentId;
    private final Money amount;
    private CancellationState state;

    private PaymentCancellation(UUID id, UUID paymentId, Money amount) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.amount.requirePositive();
        this.state = CancellationState.CANCEL_REQUESTED;
    }

    /**
     * Creates a requested cancellation.
     */
    public static PaymentCancellation request(UUID id, UUID paymentId, Money amount) {
        return new PaymentCancellation(id, paymentId, amount);
    }

    /**
     * Applies a cancellation lifecycle event.
     */
    public void apply(CancellationEvent event) {
        state = CancellationStateMachine.transition(state, event);
    }

    /**
     * Requires this cancellation to match the original payment currency.
     */
    public void requireSameCurrency(Money paymentAmount) {
        if (paymentAmount == null) {
            throw new DomainException("paymentAmount is required.");
        }
        amount.requireSameCurrency(paymentAmount);
    }

    /**
     * Returns the cancellation id.
     */
    public UUID id() {
        return id;
    }

    /**
     * Returns the original payment id.
     */
    public UUID paymentId() {
        return paymentId;
    }

    /**
     * Returns the immutable cancellation amount.
     */
    public Money amount() {
        return amount;
    }

    /**
     * Returns the current cancellation state.
     */
    public CancellationState state() {
        return state;
    }
}