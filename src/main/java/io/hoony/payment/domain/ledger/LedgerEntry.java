package io.hoony.payment.domain.ledger;

import io.hoony.payment.domain.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable money movement record used for consistency verification.
 */
public record LedgerEntry(
        UUID id,
        UUID paymentId,
        UUID cancellationId,
        LedgerEntryType type,
        Money amount,
        Instant recordedAt
) {

    /**
     * Validates ledger entry fields.
     */
    public LedgerEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        amount.requirePositive();
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    }
}