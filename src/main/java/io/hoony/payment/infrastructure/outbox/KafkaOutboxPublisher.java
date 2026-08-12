package io.hoony.payment.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hoony.payment.application.port.out.OutboxPublisher;
import io.hoony.payment.config.OutboxKafkaProperties;
import io.hoony.payment.domain.outbox.OutboxEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "payments.outbox.publisher", havingValue = "kafka")
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final OutboxKafkaProperties properties;

    public KafkaOutboxPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            OutboxKafkaProperties properties
    ) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(OutboxEvent event) {
        try {
            OutboxEventEnvelope envelope = OutboxEventEnvelope.from(
                    event,
                    properties.schemaVersion(),
                    objectMapper.readTree(event.payload())
            );
            String message = objectMapper.writeValueAsString(envelope);
            kafka.send(properties.topic(), event.aggregateId().toString(), message)
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox payload is not valid JSON.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka publish was interrupted.", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka broker did not acknowledge the outbox event.", exception);
        }
    }
}