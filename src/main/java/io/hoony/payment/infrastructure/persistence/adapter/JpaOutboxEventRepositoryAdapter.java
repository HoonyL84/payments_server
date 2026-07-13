package io.hoony.payment.infrastructure.persistence.adapter;

import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxStatus;
import io.hoony.payment.infrastructure.persistence.entity.OutboxEventEntity;
import io.hoony.payment.infrastructure.persistence.repository.JpaOutboxEventEntityRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Profile("!test")
@Repository
public class JpaOutboxEventRepositoryAdapter implements OutboxEventRepository {

    private final JpaOutboxEventEntityRepository repository;

    public JpaOutboxEventRepositoryAdapter(JpaOutboxEventEntityRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(OutboxEvent event) {
        OutboxEventEntity entity = repository.findById(event.id().toString())
                .orElseGet(() -> new OutboxEventEntity(event));
        entity.update(event);
        repository.saveAndFlush(entity);
    }

    @Override
    public List<OutboxEvent> findPending(int limit) {
        return repository.findByStatusOrderByCreatedAtAsc(
                        OutboxStatus.PENDING,
                        PageRequest.of(0, limit)
                ).stream()
                .map(OutboxEventEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public List<OutboxEvent> claimPending(
            String ownerId,
            Duration leaseDuration,
            int limit
    ) {
        long leaseMicroseconds = leaseDuration.toNanos() / 1_000;
        List<OutboxEventEntity> claimed = repository.lockClaimable(limit);
        claimed.forEach(entity -> {
            if (repository.claim(entity.id(), ownerId, leaseMicroseconds) != 1) {
                throw new IllegalStateException("Outbox claim was lost before commit.");
            }
        });
        return claimed.stream().map(OutboxEventEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean markPublished(UUID eventId, String ownerId, Instant publishedAt) {
        return repository.markPublished(eventId.toString(), ownerId, publishedAt) == 1;
    }

    @Override
    @Transactional
    public boolean releaseClaim(UUID eventId, String ownerId) {
        return repository.releaseClaim(eventId.toString(), ownerId) == 1;
    }

    @Override
    public List<OutboxEvent> findAll() {
        return repository.findAll().stream().map(OutboxEventEntity::toDomain).toList();
    }

    @Override
    public List<OutboxEvent> findPendingBefore(Instant createdBefore, int limit) {
        return repository.findClaimablePendingBefore(createdBefore, limit)
                .stream()
                .map(OutboxEventEntity::toDomain)
                .toList();
    }
}
