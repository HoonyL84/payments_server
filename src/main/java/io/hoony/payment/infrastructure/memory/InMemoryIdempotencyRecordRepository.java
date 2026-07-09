package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryIdempotencyRecordRepository implements IdempotencyRecordRepository {

    private final ConcurrentMap<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> findByKey(String key) {
        return Optional.ofNullable(records.get(key));
    }

    @Override
    public void save(IdempotencyRecord record) {
        records.put(record.key(), record);
    }
}