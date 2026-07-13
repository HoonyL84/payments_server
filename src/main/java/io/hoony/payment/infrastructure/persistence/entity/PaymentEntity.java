package io.hoony.payment.infrastructure.persistence.entity;

import io.hoony.payment.domain.money.Money;
import io.hoony.payment.domain.payment.Payment;
import io.hoony.payment.domain.payment.PaymentState;
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
@Table(name = "payments")
public class PaymentEntity {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "order_id", nullable = false, length = 128)
    private String orderId;

    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentState state;

    @Column(name = "canceled_amount_minor_units", nullable = false)
    private long canceledAmountMinorUnits;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentEntity() {
    }

    public PaymentEntity(Payment payment, Instant now) {
        this.id = payment.id().toString();
        this.createdAt = now;
        update(payment, now);
    }

    public void update(Payment payment, Instant now) {
        this.userId = payment.userId();
        this.merchantId = payment.merchantId();
        this.orderId = payment.orderId();
        this.amountMinorUnits = payment.amount().minorUnits();
        this.currency = payment.amount().currency();
        this.state = payment.state();
        this.canceledAmountMinorUnits = payment.canceledAmount().minorUnits();
        this.updatedAt = now;
    }

    public Payment toDomain() {
        return Payment.restore(
                UUID.fromString(id),
                userId,
                merchantId,
                orderId,
                new Money(amountMinorUnits, currency),
                state,
                new Money(canceledAmountMinorUnits, currency)
        );
    }

    public String getId() {
        return id;
    }
}
