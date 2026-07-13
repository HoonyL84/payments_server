package io.hoony.payment.infrastructure.outbox;

import io.hoony.payment.application.port.out.OutboxPublisher;
import io.hoony.payment.domain.outbox.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info(
                "Outbox event published. eventId={}, aggregateId={}, eventType={}",
                event.id(),
                event.aggregateId(),
                event.type()
        );
    }
}
