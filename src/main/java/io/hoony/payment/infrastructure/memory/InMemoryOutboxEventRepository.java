package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Profile("test")
@Repository
public class InMemoryOutboxEventRepository implements OutboxEventRepository {

    private final ConcurrentMap<UUID, OutboxEvent> events = new ConcurrentHashMap<>();

    @Override
    public void save(OutboxEvent event) {
        events.put(event.id(), event);
    }

    @Override
    public List<OutboxEvent> findPending(int limit) {
        return events.values().stream()
                .filter(event -> event.status() == OutboxStatus.PENDING)
                .sorted((left, right) -> left.createdAt().compareTo(right.createdAt()))
                .limit(limit)
                .toList();
    }

    @Override
    public List<OutboxEvent> findAll() {
        return List.copyOf(events.values());
    }
}
