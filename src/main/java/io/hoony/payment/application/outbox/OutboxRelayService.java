package io.hoony.payment.application.outbox;

import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.application.port.out.OutboxPublisher;
import io.hoony.payment.domain.outbox.OutboxEvent;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class OutboxRelayService {

    private final OutboxEventRepository outboxEvents;
    private final OutboxPublisher publisher;
    private final Clock clock;

    public OutboxRelayService(
            OutboxEventRepository outboxEvents,
            OutboxPublisher publisher,
            Clock clock
    ) {
        this.outboxEvents = outboxEvents;
        this.publisher = publisher;
        this.clock = clock;
    }

    public int relayPending(int limit) {
        List<OutboxEvent> pendingEvents = outboxEvents.findPending(limit);
        int publishedCount = 0;

        for (OutboxEvent event : pendingEvents) {
            try {
                publisher.publish(event);
                outboxEvents.save(event.published(Instant.now(clock)));
                publishedCount++;
            } catch (RuntimeException exception) {
                outboxEvents.save(event.publishFailed());
            }
        }
        return publishedCount;
    }
}
