package io.hoony.paymentconsumer;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class PaymentConsumerMetrics {

    private final MeterRegistry registry;

    public PaymentConsumerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void consumed(String outcome) {
        registry.counter("payments.consumer.events", "outcome", outcome).increment();
    }

    public void retry() {
        registry.counter("payments.consumer.retry").increment();
    }

    public void deadLetter() {
        registry.counter("payments.consumer.dlt").increment();
    }
}