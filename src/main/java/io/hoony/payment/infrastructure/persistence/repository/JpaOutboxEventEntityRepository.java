package io.hoony.payment.infrastructure.persistence.repository;

import io.hoony.payment.domain.outbox.OutboxStatus;
import io.hoony.payment.infrastructure.persistence.entity.OutboxEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JpaOutboxEventEntityRepository
        extends JpaRepository<OutboxEventEntity, String> {

    List<OutboxEventEntity> findByStatusOrderByCreatedAtAsc(OutboxStatus status, Pageable pageable);
}
