package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.outbox.OutboxEvent;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository {

    void save(OutboxEvent event);

    List<OutboxEvent> findPending(int limit);

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
