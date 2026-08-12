package io.hoony.paymentconsumer;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfiguration {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            PaymentConsumerMetrics metrics
    ) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition())
        );
        DefaultErrorHandler errors = new DefaultErrorHandler(recoverer, new FixedBackOff(250, 3));
        errors.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(
                    org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                    Exception exception,
                    int deliveryAttempt
            ) {
                metrics.retry();
            }

            @Override
            public void recovered(
                    org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
                    Exception exception
            ) {
                metrics.deadLetter();
            }
        });

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errors);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @Bean
    NewTopic paymentEventsTopic(
            @Value("${payments.consumer.topic:payments.events.v1}") String topic,
            @Value("${payments.consumer.partitions:6}") int partitions
    ) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsDeadLetterTopic(
            @Value("${payments.consumer.topic:payments.events.v1}") String topic,
            @Value("${payments.consumer.partitions:6}") int partitions
    ) {
        return TopicBuilder.name(topic + ".DLT").partitions(partitions).replicas(1).build();
    }

    @Bean
    java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }
}