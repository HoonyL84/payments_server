package io.hoony.payment.domain.ledger;

import io.hoony.payment.domain.money.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LedgerEntry(
        UUID id,
        UUID transactionGroupId,
        UUID paymentId,
        UUID cancellationId,
        LedgerEntryType type,
        LedgerAccount account,
        LedgerDirection direction,
        Money amount,
        Instant recordedAt
) {

    public LedgerEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(transactionGroupId, "transactionGroupId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        amount.requirePositive();
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
    }
}
