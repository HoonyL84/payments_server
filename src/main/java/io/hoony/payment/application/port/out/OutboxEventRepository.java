package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.outbox.OutboxEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository {

    void save(OutboxEvent event);

    List<OutboxEvent> findPending(int limit);

    List<OutboxEvent> claimPending(
            String ownerId,
            Duration leaseDuration,
            int limit
    );

    boolean markPublished(UUID eventId, String ownerId, Instant publishedAt);

    boolean releaseClaim(UUID eventId, String ownerId);

    List<OutboxEvent> findAll();

    default List<OutboxEvent> findPendingBefore(Instant createdBefore, int limit) {
        return findAll().stream()
                .filter(event -> event.status() == io.hoony.payment.domain.outbox.OutboxStatus.PENDING)
                .filter(event -> event.createdAt().isBefore(createdBefore))
                .sorted((left, right) -> left.createdAt().compareTo(right.createdAt()))
                .limit(limit)
                .toList();
    }
}
