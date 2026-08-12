package io.hoony.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "payments.outbox.kafka")
public record OutboxKafkaProperties(
        String topic,
        int partitions,
        int schemaVersion,
        Duration sendTimeout
) {
    public OutboxKafkaProperties {
        topic = topic == null || topic.isBlank() ? "payments.events.v1" : topic;
        partitions = partitions <= 0 ? 6 : partitions;
        schemaVersion = schemaVersion <= 0 ? 1 : schemaVersion;
        sendTimeout = sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()
                ? Duration.ofSeconds(5)
                : sendTimeout;
    }
}