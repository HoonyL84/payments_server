package io.hoony.payment.infrastructure.persistence.adapter;

import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.infrastructure.persistence.entity.IdempotencyRecordEntity;
import io.hoony.payment.infrastructure.persistence.repository.JpaIdempotencyRecordEntityRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Profile("!test")
@Repository
public class JpaIdempotencyRecordRepositoryAdapter implements IdempotencyRecordRepository {

    private final JpaIdempotencyRecordEntityRepository repository;
    private final Clock clock;

    public JpaIdempotencyRecordRepositoryAdapter(
            JpaIdempotencyRecordEntityRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Optional<IdempotencyRecord> findByScope(IdempotencyScope scope) {
        return findEntity(scope).map(IdempotencyRecordEntity::toDomain);
    }

    @Override
    public void save(IdempotencyRecord record) {
        Instant now = Instant.now(clock);
        IdempotencyRecordEntity entity = findEntity(record.scope())
                .orElseGet(() -> new IdempotencyRecordEntity(record, now));
        entity.update(record, now);
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("Idempotency request already exists.");
        }
    }

    private Optional<IdempotencyRecordEntity> findEntity(IdempotencyScope scope) {
        return repository.findByMerchantIdAndOperationAndIdempotencyKey(
                scope.merchantId(),
                scope.operation(),
                scope.key()
        );
    }
}
