package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Profile("test")
@Repository
public class InMemoryOutboxEventRepository implements OutboxEventRepository {

    private final ConcurrentMap<UUID, OutboxEvent> events = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Claim> claims = new ConcurrentHashMap<>();

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
    public synchronized List<OutboxEvent> claimPending(
            String ownerId,
            Duration leaseDuration,
            int limit
    ) {
        Instant now = Instant.now();
        Instant claimedUntil = now.plus(leaseDuration);
        List<OutboxEvent> claimed = events.values().stream()
                .filter(event -> event.status() == OutboxStatus.PENDING)
                .filter(event -> {
                    Claim claim = claims.get(event.id());
                    return claim == null || !claim.claimedUntil().isAfter(now);
                })
                .sorted((left, right) -> left.createdAt().compareTo(right.createdAt()))
                .limit(limit)
                .toList();
        claimed.forEach(event -> claims.put(event.id(), new Claim(ownerId, claimedUntil)));
        return claimed;
    }

    @Override
    public synchronized boolean markPublished(
            UUID eventId,
            String ownerId,
            Instant publishedAt
    ) {
        Claim claim = claims.get(eventId);
        OutboxEvent event = events.get(eventId);
        if (event == null || claim == null || !claim.ownerId().equals(ownerId)) {
            return false;
        }
        events.put(eventId, event.published(publishedAt));
        claims.remove(eventId);
        return true;
    }

    @Override
    public synchronized boolean releaseClaim(UUID eventId, String ownerId) {
        Claim claim = claims.get(eventId);
        OutboxEvent event = events.get(eventId);
        if (event == null || claim == null || !claim.ownerId().equals(ownerId)) {
            return false;
        }
        events.put(eventId, event.publishFailed());
        claims.remove(eventId);
        return true;
    }

    @Override
    public List<OutboxEvent> findAll() {
        return List.copyOf(events.values());
    }

    @Override
    public List<OutboxEvent> findPendingBefore(Instant createdBefore, int limit) {
        return events.values().stream()
                .filter(event -> event.status() == OutboxStatus.PENDING)
                .filter(event -> event.createdAt().isBefore(createdBefore))
                .sorted((left, right) -> left.createdAt().compareTo(right.createdAt()))
                .limit(limit)
                .toList();
    }

    private record Claim(String ownerId, Instant claimedUntil) {
    }
}
