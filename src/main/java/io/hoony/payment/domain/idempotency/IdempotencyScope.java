package io.hoony.payment.domain.idempotency;

import io.hoony.payment.domain.common.DomainException;

import java.util.Objects;

public record IdempotencyScope(
        String merchantId,
        IdempotencyOperation operation,
        String key
) {
    public IdempotencyScope {
        merchantId = requireText(merchantId, "merchantId");
        Objects.requireNonNull(operation, "operation must not be null");
        key = requireText(key, "key");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainException(fieldName + " is required.");
        }
        return value.trim();
    }
}
