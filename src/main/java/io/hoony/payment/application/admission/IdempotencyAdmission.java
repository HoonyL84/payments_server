package io.hoony.payment.application.admission;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.idempotency.IdempotencyOperation;

public record IdempotencyAdmission(
        IdempotencyOperation operation,
        String ownerId,
        String idempotencyKey,
        String fingerprint
) {
    public IdempotencyAdmission {
        if (operation == null
                || ownerId == null || ownerId.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank()
                || fingerprint == null || fingerprint.isBlank()) {
            throw new DomainException("Idempotency admission is incomplete.");
        }
    }
}
