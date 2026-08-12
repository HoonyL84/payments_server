package io.hoony.paymentconsumer;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record PaymentEventEnvelope(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        JsonNode payload
) {
}