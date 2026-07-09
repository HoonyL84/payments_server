package io.hoony.payment.application.approval;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.money.Money;

/**
 * Command accepted by the approval use case after request entry validation.
 */
public record ApprovePaymentCommand(
        String idempotencyKey,
        String userId,
        String merchantId,
        String orderId,
        Money amount
) {

    /**
     * Validates the approval command.
     */
    public ApprovePaymentCommand {
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        userId = requireText(userId, "userId");
        merchantId = requireText(merchantId, "merchantId");
        orderId = requireText(orderId, "orderId");
        if (amount == null) {
            throw new DomainException("amount is required.");
        }
        amount.requirePositive();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainException(fieldName + " is required.");
        }
        return value.trim();
    }
}