package io.hoony.payment.domain.attempt;

import io.hoony.payment.domain.common.DomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of one PG interaction attempt.
 */
public record PaymentAttempt(
        UUID id,
        UUID paymentId,
        UUID cancellationId,
        PaymentOperation operation,
        PaymentAttemptResult result,
        Instant attemptedAt
) {

    /**
     * Validates attempt identity and operation result.
     */
    public PaymentAttempt {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(attemptedAt, "attemptedAt must not be null");

        if ((operation == PaymentOperation.CANCEL || operation == PaymentOperation.CONFIRM_CANCEL)
                && cancellationId == null) {
            throw new DomainException("Cancellation attempt requires cancellationId.");
        }
    }
}