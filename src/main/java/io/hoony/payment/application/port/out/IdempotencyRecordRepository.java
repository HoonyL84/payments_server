package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.idempotency.IdempotencyRecord;

import java.util.Optional;

/**
 * Stores idempotency records for request deduplication.
 */
public interface IdempotencyRecordRepository {

    /**
     * Finds an idempotency record by key.
     */
    Optional<IdempotencyRecord> findByKey(String key);

    /**
     * Saves an idempotency record.
     */
    void save(IdempotencyRecord record);
}