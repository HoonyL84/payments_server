package io.hoony.payment.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.hoony.payment.domain.outbox.OutboxEvent;

import java.time.Instant;
import java.util.UUID;

public record OutboxEventEnvelope(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        JsonNode payload
) {
    public static OutboxEventEnvelope from(OutboxEvent event, int schemaVersion, JsonNode payload) {
        return new OutboxEventEnvelope(
                event.id(),
                event.aggregateId(),
                event.type().name(),
                schemaVersion,
                event.createdAt(),
                payload
        );
    }
}