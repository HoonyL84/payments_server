package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import io.hoony.payment.domain.idempotency.IdempotencyScope;

import java.util.Optional;

public interface IdempotencyRecordRepository {

    Optional<IdempotencyRecord> findByScope(IdempotencyScope scope);

    void save(IdempotencyRecord record);
}
