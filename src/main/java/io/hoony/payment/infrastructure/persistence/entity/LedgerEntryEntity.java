package io.hoony.payment.infrastructure.persistence.entity;

import io.hoony.payment.domain.ledger.LedgerAccount;
import io.hoony.payment.domain.ledger.LedgerDirection;
import io.hoony.payment.domain.ledger.LedgerEntry;
import io.hoony.payment.domain.ledger.LedgerEntryType;
import io.hoony.payment.domain.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntryEntity {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "transaction_group_id", nullable = false, columnDefinition = "char(36)")
    private String transactionGroupId;

    @Column(name = "payment_id", nullable = false, columnDefinition = "char(36)")
    private String paymentId;

    @Column(name = "cancellation_id", columnDefinition = "char(36)")
    private String cancellationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private LedgerEntryType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LedgerDirection direction;

    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected LedgerEntryEntity() {
    }

    public LedgerEntryEntity(LedgerEntry entry) {
        this.id = entry.id().toString();
        this.transactionGroupId = entry.transactionGroupId().toString();
        this.paymentId = entry.paymentId().toString();
        this.cancellationId = entry.cancellationId() == null ? null : entry.cancellationId().toString();
        this.type = entry.type();
        this.account = entry.account();
        this.direction = entry.direction();
        this.amountMinorUnits = entry.amount().minorUnits();
        this.currency = entry.amount().currency();
        this.recordedAt = entry.recordedAt();
    }

    public LedgerEntry toDomain() {
        return new LedgerEntry(
                UUID.fromString(id),
                UUID.fromString(transactionGroupId),
                UUID.fromString(paymentId),
                cancellationId == null ? null : UUID.fromString(cancellationId),
                type,
                account,
                direction,
                new Money(amountMinorUnits, currency),
                recordedAt
        );
    }
}
