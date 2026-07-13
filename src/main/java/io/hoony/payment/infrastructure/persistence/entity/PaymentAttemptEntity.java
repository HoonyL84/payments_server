package io.hoony.payment.infrastructure.persistence.entity;

import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.domain.attempt.PaymentAttemptResult;
import io.hoony.payment.domain.attempt.PaymentOperation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttemptEntity {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "payment_id", nullable = false, columnDefinition = "char(36)")
    private String paymentId;

    @Column(name = "cancellation_id", columnDefinition = "char(36)")
    private String cancellationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentOperation operation;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_request_id", nullable = false, length = 128)
    private String providerRequestId;

    @Column(name = "provider_transaction_id", length = 128)
    private String providerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentAttemptResult result;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PaymentAttemptEntity() {
    }

    public PaymentAttemptEntity(PaymentAttempt attempt) {
        this.id = attempt.id().toString();
        this.paymentId = attempt.paymentId().toString();
        this.cancellationId = attempt.cancellationId() == null ? null : attempt.cancellationId().toString();
        this.operation = attempt.operation();
        this.provider = attempt.provider();
        this.providerRequestId = attempt.providerRequestId();
        this.startedAt = attempt.startedAt();
        update(attempt);
    }

    public void update(PaymentAttempt attempt) {
        this.providerTransactionId = attempt.providerTransactionId();
        this.result = attempt.result();
        this.errorCode = attempt.errorCode();
        this.completedAt = attempt.completedAt();
    }

    public PaymentAttempt toDomain() {
        return PaymentAttempt.restore(
                UUID.fromString(id),
                UUID.fromString(paymentId),
                cancellationId == null ? null : UUID.fromString(cancellationId),
                operation,
                provider,
                providerRequestId,
                result,
                providerTransactionId,
                errorCode,
                startedAt,
                completedAt
        );
    }
}
