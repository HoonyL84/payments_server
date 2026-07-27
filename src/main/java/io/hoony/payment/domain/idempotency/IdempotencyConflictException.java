package io.hoony.payment.domain.idempotency;

import io.hoony.payment.domain.common.ResourceConflictException;

public class IdempotencyConflictException extends ResourceConflictException {

    private final IdempotencyOperation operation;

    public IdempotencyConflictException(IdempotencyOperation operation) {
        super("Idempotency key reused with different request fingerprint.");
        this.operation = operation;
    }

    public IdempotencyOperation operation() {
        return operation;
    }
}
