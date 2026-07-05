package io.hoony.payment.domain.outbox;

import io.hoony.payment.domain.common.DomainException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain event stored before asynchronous publication.
 */
public record OutboxEvent(
        UUID id,
        UUID aggregateId,
        OutboxEventType type,
        String payload,
        OutboxStatus status,
        Instant createdAt
) {

    /**
     * Validates outbox event fields.
     */
    public OutboxEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (payload == null || payload.isBlank()) {
            throw new DomainException("Outbox payload is required.");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Creates a pending outbox event.
     */
    public static OutboxEvent pending(UUID id, UUID aggregateId, OutboxEventType type, String payload, Instant createdAt) {
        return new OutboxEvent(id, aggregateId, type, payload, OutboxStatus.PENDING, createdAt);
    }
}