package io.hoony.payment.infrastructure.persistence.entity;

import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import io.hoony.payment.domain.idempotency.IdempotencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IdempotencyOperation operation;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(IdempotencyRecord record, Instant now) {
        this.merchantId = record.scope().merchantId();
        this.operation = record.scope().operation();
        this.idempotencyKey = record.scope().key();
        this.fingerprint = record.fingerprint();
        this.createdAt = record.createdAt();
        update(record, now);
    }

    public void update(IdempotencyRecord record, Instant now) {
        this.status = record.status();
        this.responseBody = record.responseBody().orElse(null);
        this.updatedAt = now;
    }

    public IdempotencyRecord toDomain() {
        return IdempotencyRecord.restore(
                new IdempotencyScope(merchantId, operation, idempotencyKey),
                fingerprint,
                status,
                responseBody,
                createdAt
        );
    }
}
