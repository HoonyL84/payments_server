package io.hoony.payment.application.cancellation;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.money.Money;

import java.util.UUID;

public record CancelPaymentCommand(
        String idempotencyKey,
        UUID paymentId,
        Money amount
) {
    public CancelPaymentCommand {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException("idempotencyKey is required.");
        }
        idempotencyKey = idempotencyKey.trim();
        if (idempotencyKey.length() > 128) {
            throw new DomainException("idempotencyKey must not exceed 128 characters.");
        }
        if (paymentId == null) {
            throw new DomainException("paymentId is required.");
        }
        if (amount == null) {
            throw new DomainException("amount is required.");
        }
        amount.requirePositive();
    }
}