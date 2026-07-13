package io.hoony.payment.infrastructure.persistence.adapter;

import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxStatus;
import io.hoony.payment.infrastructure.persistence.entity.OutboxEventEntity;
import io.hoony.payment.infrastructure.persistence.repository.JpaOutboxEventEntityRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;

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
    public List<OutboxEvent> findAll() {
        return repository.findAll().stream().map(OutboxEventEntity::toDomain).toList();
    }
}
