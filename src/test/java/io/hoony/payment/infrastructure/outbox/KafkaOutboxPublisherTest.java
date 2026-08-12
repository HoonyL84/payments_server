package io.hoony.payment.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hoony.payment.config.OutboxKafkaProperties;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxEventType;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaOutboxPublisherTest {

    @Test
    void usesAggregateIdAsKeyAndWaitsForBrokerFuture() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(
                kafka,
                new ObjectMapper().findAndRegisterModules(),
                new OutboxKafkaProperties("payments.events.v1", 6, 1, Duration.ofSeconds(1))
        );
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.pending(
                UUID.randomUUID(),
                aggregateId,
                OutboxEventType.PAYMENT_APPROVED,
                "{\"paymentId\":\"" + aggregateId + "\"}",
                Instant.parse("2026-07-31T10:00:00Z")
        );

        publisher.publish(event);

        verify(kafka).send(
                org.mockito.ArgumentMatchers.eq("payments.events.v1"),
                org.mockito.ArgumentMatchers.eq(aggregateId.toString()),
                anyString()
        );
    }
}