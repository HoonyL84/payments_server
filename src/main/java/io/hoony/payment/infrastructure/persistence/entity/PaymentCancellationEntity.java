package io.hoony.payment.infrastructure.persistence.entity;

import io.hoony.payment.domain.cancellation.CancellationState;
import io.hoony.payment.domain.cancellation.PaymentCancellation;
import io.hoony.payment.domain.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_cancellations")
public class PaymentCancellationEntity {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "payment_id", nullable = false, columnDefinition = "char(36)")
    private String paymentId;

    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CancellationState state;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentCancellationEntity() {
    }

    public PaymentCancellationEntity(PaymentCancellation cancellation, Instant now) {
        this.id = cancellation.id().toString();
        this.paymentId = cancellation.paymentId().toString();
        this.amountMinorUnits = cancellation.amount().minorUnits();
        this.currency = cancellation.amount().currency();
        this.createdAt = now;
        update(cancellation, now);
    }

    public void update(PaymentCancellation cancellation, Instant now) {
        this.state = cancellation.state();
        this.updatedAt = now;
    }

    public PaymentCancellation toDomain() {
        return PaymentCancellation.restore(
                UUID.fromString(id),
                UUID.fromString(paymentId),
                new Money(amountMinorUnits, currency),
                state
        );
    }
}