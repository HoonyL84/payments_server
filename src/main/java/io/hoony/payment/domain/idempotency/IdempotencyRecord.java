package io.hoony.payment.domain.idempotency;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.common.ResourceConflictException;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public class IdempotencyRecord {

    private final IdempotencyScope scope;
    private final String fingerprint;
    private IdempotencyStatus status;
    private String responseBody;
    private final Instant createdAt;

    private IdempotencyRecord(
            IdempotencyScope scope,
            String fingerprint,
            IdempotencyStatus status,
            String responseBody,
            Instant createdAt
    ) {
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.fingerprint = requireText(fingerprint, "fingerprint");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.responseBody = responseBody;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static IdempotencyRecord start(IdempotencyScope scope, String fingerprint, Instant createdAt) {
        return new IdempotencyRecord(
                scope,
                fingerprint,
                IdempotencyStatus.PROCESSING,
                null,
                createdAt
        );
    }

    public static IdempotencyRecord restore(
            IdempotencyScope scope,
            String fingerprint,
            IdempotencyStatus status,
            String responseBody,
            Instant createdAt
    ) {
        return new IdempotencyRecord(scope, fingerprint, status, responseBody, createdAt);
    }

    public void requireSameFingerprint(String otherFingerprint) {
        if (!fingerprint.equals(requireText(otherFingerprint, "otherFingerprint"))) {
            throw new ResourceConflictException(
                    "Idempotency key reused with different request fingerprint."
            );
        }
    }

    public void complete(String responseBody) {
        this.responseBody = requireText(responseBody, "responseBody");
        this.status = IdempotencyStatus.COMPLETED;
    }

    public void fail(String responseBody) {
        this.responseBody = requireText(responseBody, "responseBody");
        this.status = IdempotencyStatus.FAILED;
    }

    public IdempotencyScope scope() {
        return scope;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public IdempotencyStatus status() {
        return status;
    }

    public Optional<String> responseBody() {
        return Optional.ofNullable(responseBody);
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new DomainException(fieldName + " is required.");
        }
        return value.trim();
    }
}
