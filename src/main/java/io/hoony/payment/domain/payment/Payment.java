package io.hoony.payment.domain.payment;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.money.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * Payment aggregate that owns approval amount and lifecycle state.
 */
public class Payment {

    private final UUID id;
    private final String userId;
    private final String merchantId;
    private final String orderId;
    private final Money amount;
    private PaymentState state;
    private Money canceledAmount;

    private Payment(UUID id, String userId, String merchantId, String orderId, Money amount) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = requireText(userId, "userId");
        this.merchantId = requireText(merchantId, "merchantId");
        this.orderId = requireText(orderId, "orderId");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.amount.requirePositive();
        this.state = PaymentState.REQUESTED;
        this.canceledAmount = new Money(0, amount.currency());
    }

    /**
     * Creates a new requested payment.
     */
    public static Payment request(UUID id, String userId, String merchantId, String orderId, Money amount) {
        return new Payment(id, userId, merchantId, orderId, amount);
    }

    /**
     * Applies a state event to this payment.
     */
    public void apply(PaymentEvent event) {
        state = PaymentStateMachine.transition(state, event);
    }

    /**
     * Records a cancellation amount after cancellation succeeds.
     */
    public void recordCanceledAmount(Money cancelAmount) {
        if (state != PaymentState.APPROVED) {
            throw new DomainException("Only approved payments can record cancellation amount.");
        }
        Objects.requireNonNull(cancelAmount, "cancelAmount must not be null");
        cancelAmount.requirePositive();
        amount.requireSameCurrency(cancelAmount);

        Money nextCanceledAmount = canceledAmount.plus(cancelAmount);
        if (nextCanceledAmount.isGreaterThan(amount)) {
            throw new DomainException("Canceled amount cannot exceed approved amount.");
        }
        canceledAmount = nextCanceledAmount;
    }

    /**
     * Returns the payment id.
     */
    public UUID id() {
        return id;
    }

    /**
     * Returns the user id.
     */
    public String userId() {
        return userId;
    }

    /**
     * Returns the merchant id.
     */
    public String merchantId() {
        return merchantId;
    }

    /**
     * Returns the external order id.
     */
    public String orderId() {
        return orderId;
    }

    /**
     * Returns the immutable approved amount.
     */
    public Money amount() {
        return amount;
    }

    /**
     * Returns the current lifecycle state.
     */
    public PaymentState state() {
        return state;
    }

    /**
     * Returns the cumulative canceled amount.
     */
    public Money canceledAmount() {
        return canceledAmount;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainException(fieldName + " is required.");
        }
        return value.trim();
    }
}