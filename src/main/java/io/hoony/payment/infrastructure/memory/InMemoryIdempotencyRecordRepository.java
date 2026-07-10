package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryIdempotencyRecordRepository implements IdempotencyRecordRepository {

    private final ConcurrentMap<IdempotencyScope, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> findByScope(IdempotencyScope scope) {
        return Optional.ofNullable(records.get(scope));
    }

    @Override
    public void save(IdempotencyRecord record) {
        records.put(record.scope(), record);
    }
}
