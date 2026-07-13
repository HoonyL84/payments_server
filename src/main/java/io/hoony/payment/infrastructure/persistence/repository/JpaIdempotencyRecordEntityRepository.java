package io.hoony.payment.infrastructure.persistence.repository;

import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.hoony.payment.infrastructure.persistence.entity.IdempotencyRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaIdempotencyRecordEntityRepository
        extends JpaRepository<IdempotencyRecordEntity, Long> {

    Optional<IdempotencyRecordEntity> findByMerchantIdAndOperationAndIdempotencyKey(
            String merchantId,
            IdempotencyOperation operation,
            String idempotencyKey
    );
}
