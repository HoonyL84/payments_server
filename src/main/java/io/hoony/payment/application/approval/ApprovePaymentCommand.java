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
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 128);
        userId = requireText(userId, "userId", 64);
        merchantId = requireText(merchantId, "merchantId", 64);
        orderId = requireText(orderId, "orderId", 128);
        if (amount == null) {
            throw new DomainException("amount is required.");
        }
        amount.requirePositive();
    }

    private static String requireText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new DomainException(fieldName + " is required.");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new DomainException(fieldName + " is too long.");
        }
        return normalized;
    }
}