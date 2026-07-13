package io.hoony.payment.domain.attempt;

import io.hoony.payment.domain.common.DomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class PaymentAttempt {

    private final UUID id;
    private final UUID paymentId;
    private final UUID cancellationId;
    private final PaymentOperation operation;
    private final String provider;
    private final String providerRequestId;
    private PaymentAttemptResult result;
    private String providerTransactionId;
    private String errorCode;
    private final Instant startedAt;
    private Instant completedAt;

    private PaymentAttempt(
            UUID id,
            UUID paymentId,
            UUID cancellationId,
            PaymentOperation operation,
            String provider,
            String providerRequestId,
            PaymentAttemptResult result,
            String providerTransactionId,
            String errorCode,
            Instant startedAt,
            Instant completedAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
        this.cancellationId = cancellationId;
        this.operation = Objects.requireNonNull(operation, "operation must not be null");
        this.provider = requireText(provider, "provider");
        this.providerRequestId = requireText(providerRequestId, "providerRequestId");
        this.result = Objects.requireNonNull(result, "result must not be null");
        this.providerTransactionId = providerTransactionId;
        this.errorCode = errorCode;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.completedAt = completedAt;

        if ((operation == PaymentOperation.CANCEL || operation == PaymentOperation.CONFIRM_CANCEL)
                && cancellationId == null) {
            throw new DomainException("Cancellation attempt requires cancellationId.");
        }
    }

    public static PaymentAttempt start(
            UUID id,
            UUID paymentId,
            UUID cancellationId,
            PaymentOperation operation,
            String provider,
            String providerRequestId,
            Instant startedAt
    ) {
        return new PaymentAttempt(
                id,
                paymentId,
                cancellationId,
                operation,
                provider,
                providerRequestId,
                PaymentAttemptResult.PROCESSING,
                null,
                null,
                startedAt,
                null
        );
    }

    public static PaymentAttempt restore(
            UUID id,
            UUID paymentId,
            UUID cancellationId,
            PaymentOperation operation,
            String provider,
            String providerRequestId,
            PaymentAttemptResult result,
            String providerTransactionId,
            String errorCode,
            Instant startedAt,
            Instant completedAt
    ) {
        return new PaymentAttempt(
                id,
                paymentId,
                cancellationId,
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

    public void complete(
            PaymentAttemptResult result,
            String providerTransactionId,
            String errorCode,
            Instant completedAt
    ) {
        if (this.result != PaymentAttemptResult.PROCESSING) {
            throw new DomainException("Payment attempt is already completed.");
        }
        if (result == PaymentAttemptResult.PROCESSING) {
            throw new DomainException("Completed attempt cannot remain processing.");
        }
        this.result = Objects.requireNonNull(result, "result must not be null");
        this.providerTransactionId = providerTransactionId;
        this.errorCode = errorCode;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    public UUID id() {
        return id;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public UUID cancellationId() {
        return cancellationId;
    }

    public PaymentOperation operation() {
        return operation;
    }

    public String provider() {
        return provider;
    }

    public String providerRequestId() {
        return providerRequestId;
    }

    public PaymentAttemptResult result() {
        return result;
    }

    public String providerTransactionId() {
        return providerTransactionId;
    }

    public String errorCode() {
        return errorCode;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainException(fieldName + " is required.");
        }
        return value.trim();
    }
}
