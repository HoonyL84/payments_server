package io.hoony.payment.application.outbox;

import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.application.port.out.OutboxPublisher;
import io.hoony.payment.config.OutboxRelayProperties;
import io.hoony.payment.config.RuntimeInstanceProperties;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.observability.PaymentMetrics;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxRelayService {

    private final OutboxEventRepository outboxEvents;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final RuntimeInstanceProperties runtime;
    private final OutboxRelayProperties properties;
    private final PaymentMetrics metrics;

    public OutboxRelayService(
            OutboxEventRepository outboxEvents,
            OutboxPublisher publisher,
            Clock clock,
            RuntimeInstanceProperties runtime,
            OutboxRelayProperties properties,
            PaymentMetrics metrics
    ) {
        this.outboxEvents = outboxEvents;
        this.publisher = publisher;
        this.clock = clock;
        this.runtime = runtime;
        this.properties = properties;
        this.metrics = metrics;
    }

    public int relayPending(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Outbox relay limit must be positive.");
        }

        String claimOwner = runtime.instanceId() + ":" + UUID.randomUUID();
        List<OutboxEvent> claimedEvents = metrics.time(
                "payments.outbox.claim.duration",
                "claim",
                () -> outboxEvents.claimPending(
                        claimOwner,
                        properties.leaseDuration(),
                        limit
                )
        );
        metrics.outboxClaimed(claimedEvents.size());
        int publishedCount = 0;

        for (OutboxEvent event : claimedEvents) {
            try {
                publisher.publish(event);
                if (outboxEvents.markPublished(
                        event.id(),
                        claimOwner,
                        Instant.now(clock)
                )) {
                    publishedCount++;
                } else {
                    metrics.outboxClaimLost("complete");
                }
            } catch (RuntimeException exception) {
                if (!outboxEvents.releaseClaim(event.id(), claimOwner)) {
                    metrics.outboxClaimLost("release");
                }
            }
        }
        return publishedCount;
    }
}
