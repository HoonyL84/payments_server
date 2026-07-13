package io.hoony.payment.domain.payment;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.money.Money;

import java.util.Objects;
import java.util.UUID;

public class Payment {

    private final UUID id;
    private final String userId;
    private final String merchantId;
    private final String orderId;
    private final Money amount;
    private PaymentState state;
    private Money canceledAmount;

    private Payment(
            UUID id,
            String userId,
            String merchantId,
            String orderId,
            Money amount,
            PaymentState state,
            Money canceledAmount
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = requireText(userId, "userId");
        this.merchantId = requireText(merchantId, "merchantId");
        this.orderId = requireText(orderId, "orderId");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.amount.requirePositive();
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.canceledAmount = Objects.requireNonNull(canceledAmount, "canceledAmount must not be null");
        this.amount.requireSameCurrency(canceledAmount);
        if (canceledAmount.isGreaterThan(amount)) {
            throw new DomainException("Canceled amount cannot exceed approved amount.");
        }
    }

    public static Payment request(UUID id, String userId, String merchantId, String orderId, Money amount) {
        return new Payment(
                id,
                userId,
                merchantId,
                orderId,
                amount,
                PaymentState.REQUESTED,
                new Money(0, amount.currency())
        );
    }

    public static Payment restore(
            UUID id,
            String userId,
            String merchantId,
            String orderId,
            Money amount,
            PaymentState state,
            Money canceledAmount
    ) {
        return new Payment(id, userId, merchantId, orderId, amount, state, canceledAmount);
    }

    public void apply(PaymentEvent event) {
        state = PaymentStateMachine.transition(state, event);
    }

    public void requireCancellationCapacity(Money cancelAmount, Money inFlightAmount) {
        if (state != PaymentState.APPROVED) {
            throw new DomainException("Only approved payments can be canceled.");
        }
        Objects.requireNonNull(cancelAmount, "cancelAmount must not be null");
        Objects.requireNonNull(inFlightAmount, "inFlightAmount must not be null");
        cancelAmount.requirePositive();
        amount.requireSameCurrency(cancelAmount);
        amount.requireSameCurrency(inFlightAmount);

        Money committedAndReserved = canceledAmount.plus(inFlightAmount).plus(cancelAmount);
        if (committedAndReserved.isGreaterThan(amount)) {
            throw new DomainException("Cancellation amount exceeds remaining approved amount.");
        }
    }
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

    public UUID id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String orderId() {
        return orderId;
    }

    public Money amount() {
        return amount;
    }

    public PaymentState state() {
        return state;
    }

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
