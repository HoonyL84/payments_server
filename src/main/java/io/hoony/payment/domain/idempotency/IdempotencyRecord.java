package io.hoony.payment.domain.idempotency;

import io.hoony.payment.domain.common.DomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Request deduplication record keyed by idempotency key and request fingerprint.
 */
public class IdempotencyRecord {

    private final String key;
    private final String fingerprint;
    private IdempotencyStatus status;
    private String responseBody;
    private final Instant createdAt;

    private IdempotencyRecord(String key, String fingerprint, Instant createdAt) {
        this.key = requireText(key, "key");
        this.fingerprint = requireText(fingerprint, "fingerprint");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.status = IdempotencyStatus.PROCESSING;
    }

    /**
     * Creates a processing idempotency record.
     */
    public static IdempotencyRecord start(String key, String fingerprint, Instant createdAt) {
        return new IdempotencyRecord(key, fingerprint, createdAt);
    }

    /**
     * Fails fast when the same key is reused with a different request fingerprint.
     */
    public void requireSameFingerprint(String otherFingerprint) {
        if (!fingerprint.equals(requireText(otherFingerprint, "otherFingerprint"))) {
            throw new DomainException("Idempotency key reused with different request fingerprint.");
        }
    }

    /**
     * Marks the request as completed and stores a reusable response.
     */
    public void complete(String responseBody) {
        this.responseBody = requireText(responseBody, "responseBody");
        this.status = IdempotencyStatus.COMPLETED;
    }

    /**
     * Marks the request as failed with a reusable response.
     */
    public void fail(String responseBody) {
        this.responseBody = requireText(responseBody, "responseBody");
        this.status = IdempotencyStatus.FAILED;
    }

    /**
     * Returns the idempotency key.
     */
    public String key() {
        return key;
    }

    /**
     * Returns the request fingerprint.
     */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * Returns the processing status.
     */
    public IdempotencyStatus status() {
        return status;
    }

    /**
     * Returns the stored response if one exists.
     */
    public Optional<String> responseBody() {
        return Optional.ofNullable(responseBody);
    }

    /**
     * Returns the creation time.
     */
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