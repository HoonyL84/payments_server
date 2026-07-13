package io.hoony.payment.domain.outbox;

import io.hoony.payment.domain.common.DomainException;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OutboxEvent(
        UUID id,
        UUID aggregateId,
        OutboxEventType type,
        String payload,
        OutboxStatus status,
        int publishAttempts,
        Instant createdAt,
        Instant publishedAt
) {

    public OutboxEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (payload == null || payload.isBlank()) {
            throw new DomainException("Outbox payload is required.");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (publishAttempts < 0) {
            throw new DomainException("publishAttempts must not be negative.");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static OutboxEvent pending(
            UUID id,
            UUID aggregateId,
            OutboxEventType type,
            String payload,
            Instant createdAt
    ) {
        return new OutboxEvent(
                id,
                aggregateId,
                type,
                payload,
                OutboxStatus.PENDING,
                0,
                createdAt,
                null
        );
    }

    public OutboxEvent published(Instant publishedAt) {
        return new OutboxEvent(
                id,
                aggregateId,
                type,
                payload,
                OutboxStatus.PUBLISHED,
                publishAttempts + 1,
                createdAt,
                Objects.requireNonNull(publishedAt, "publishedAt must not be null")
        );
    }

    public Optional<Duration> publishLag() {
        if (publishedAt == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(createdAt, publishedAt));
    }
    public OutboxEvent publishFailed() {
        return new OutboxEvent(
                id,
                aggregateId,
                type,
                payload,
                OutboxStatus.PENDING,
                publishAttempts + 1,
                createdAt,
                null
        );
    }
}
