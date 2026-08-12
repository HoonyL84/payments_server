package io.hoony.payment.infrastructure.outbox;

import io.hoony.payment.config.OutboxKafkaProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "payments.outbox.publisher", havingValue = "kafka")
public class KafkaTopicConfiguration {

    @Bean
    NewTopic paymentEventsTopic(OutboxKafkaProperties properties) {
        return TopicBuilder.name(properties.topic())
                .partitions(properties.partitions())
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic paymentEventsDeadLetterTopic(OutboxKafkaProperties properties) {
        return TopicBuilder.name(properties.topic() + ".DLT")
                .partitions(properties.partitions())
                .replicas(1)
                .build();
    }
}