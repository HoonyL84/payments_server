package io.hoony.paymentconsumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListener {

    private final ObjectMapper objectMapper;
    private final PaymentEventHandler handler;
    private final PaymentConsumerMetrics metrics;

    public PaymentEventListener(
            ObjectMapper objectMapper,
            PaymentEventHandler handler,
            PaymentConsumerMetrics metrics
    ) {
        this.objectMapper = objectMapper;
        this.handler = handler;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "${payments.consumer.topic:payments.events.v1}",
            groupId = PaymentEventHandler.CONSUMER_GROUP
    )
    public void consume(String message) throws JsonProcessingException {
        PaymentEventHandler.Result result =
                handler.handle(objectMapper.readValue(message, PaymentEventEnvelope.class));
        metrics.consumed(result.name().toLowerCase());
    }
}