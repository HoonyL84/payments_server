package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.outbox.OutboxEvent;

public interface OutboxPublisher {

    void publish(OutboxEvent event);
}
