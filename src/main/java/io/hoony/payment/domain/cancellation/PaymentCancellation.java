package io.hoony.payment.domain.cancellation;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.money.Money;

import java.util.Objects;
import java.util.UUID;

public class PaymentCancellation {

    private final UUID id;
    private final UUID paymentId;
    private final Money amount;
    private CancellationState state;

    private PaymentCancellation(UUID id, UUID paymentId, Money amount, CancellationState state) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.amount.requirePositive();
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    public static PaymentCancellation request(UUID id, UUID paymentId, Money amount) {
        return new PaymentCancellation(id, paymentId, amount, CancellationState.CANCEL_REQUESTED);
    }

    public static PaymentCancellation restore(
            UUID id,
            UUID paymentId,
            Money amount,
            CancellationState state
    ) {
        return new PaymentCancellation(id, paymentId, amount, state);
    }

    public void apply(CancellationEvent event) {
        state = CancellationStateMachine.transition(state, event);
    }

    public void requireSameCurrency(Money paymentAmount) {
        if (paymentAmount == null) {
            throw new DomainException("paymentAmount is required.");
        }
        amount.requireSameCurrency(paymentAmount);
    }

    public UUID id() {
        return id;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public Money amount() {
        return amount;
    }

    public CancellationState state() {
        return state;
    }
}