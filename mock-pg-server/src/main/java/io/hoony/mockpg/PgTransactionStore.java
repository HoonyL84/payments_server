package io.hoony.mockpg;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PgTransactionStore {

    private final JdbcTemplate jdbc;

    public PgTransactionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Transaction saveOrGet(Command command, MockPgBehavior.Operation operation, String fingerprint, String status) {
        Optional<Transaction> existing = find(command.providerRequestId());
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(fingerprint)) {
                throw new PayloadConflictException(command.providerRequestId());
            }
            return existing.get();
        }

        Instant now = Instant.now();
        String transactionId = "mock-pg-" + UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO pg_transactions(
                        provider_request_id, operation, payment_id, cancellation_id, merchant_id, order_id,
                        amount_minor_units, currency, original_provider_transaction_id, fingerprint,
                        status, provider_transaction_id, error_code, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    command.providerRequestId(),
                    operation.name(),
                    command.paymentId().toString(),
                    command.cancellationId() == null ? null : command.cancellationId().toString(),
                    command.merchantId(),
                    command.orderId(),
                    command.amountMinorUnits(),
                    command.currency(),
                    command.originalProviderTransactionId(),
                    fingerprint,
                    status,
                    "DECLINED".equals(status) ? null : transactionId,
                    "DECLINED".equals(status) ? "DECLINED" : null,
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
            return find(command.providerRequestId()).orElseThrow();
        } catch (DuplicateKeyException exception) {
            Transaction concurrent = find(command.providerRequestId()).orElseThrow();
            if (!concurrent.fingerprint().equals(fingerprint)) {
                throw new PayloadConflictException(command.providerRequestId());
            }
            return concurrent;
        }
    }

    public Optional<Transaction> find(String providerRequestId) {
        return jdbc.query("""
                SELECT provider_request_id, operation, payment_id, cancellation_id, fingerprint,
                       status, provider_transaction_id, error_code, created_at
                  FROM pg_transactions
                 WHERE provider_request_id = ?
                """, (rs, row) -> new Transaction(
                rs.getString("provider_request_id"),
                MockPgBehavior.Operation.valueOf(rs.getString("operation")),
                UUID.fromString(rs.getString("payment_id")),
                rs.getString("cancellation_id") == null ? null : UUID.fromString(rs.getString("cancellation_id")),
                rs.getString("fingerprint"),
                rs.getString("status"),
                rs.getString("provider_transaction_id"),
                rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant()
        ), providerRequestId).stream().findFirst();
    }

    public List<Transaction> findAfter(Instant from, String cursor, int limit) {
        Instant cursorTime = cursor == null ? from : find(cursor).map(Transaction::createdAt).orElse(from);
        String cursorValue = cursor == null ? "" : cursor;
        return jdbc.query("""
                SELECT provider_request_id, operation, payment_id, cancellation_id, fingerprint,
                       status, provider_transaction_id, error_code, created_at
                  FROM pg_transactions
                 WHERE created_at >= ?
                   AND (created_at > ? OR (created_at = ? AND provider_request_id > ?))
                 ORDER BY created_at, provider_request_id
                 LIMIT ?
                """, (rs, row) -> new Transaction(
                rs.getString("provider_request_id"),
                MockPgBehavior.Operation.valueOf(rs.getString("operation")),
                UUID.fromString(rs.getString("payment_id")),
                rs.getString("cancellation_id") == null ? null : UUID.fromString(rs.getString("cancellation_id")),
                rs.getString("fingerprint"),
                rs.getString("status"),
                rs.getString("provider_transaction_id"),
                rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant()
        ), Timestamp.from(from), Timestamp.from(cursorTime), Timestamp.from(cursorTime), cursorValue, limit);
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM pg_transactions");
    }

    public record Command(
            String providerRequestId,
            UUID paymentId,
            UUID cancellationId,
            String merchantId,
            String orderId,
            long amountMinorUnits,
            String currency,
            String originalProviderTransactionId
    ) {
    }

    public record Transaction(
            String providerRequestId,
            MockPgBehavior.Operation operation,
            UUID paymentId,
            UUID cancellationId,
            String fingerprint,
            String status,
            String providerTransactionId,
            String errorCode,
            Instant createdAt
    ) {
    }

    public static class PayloadConflictException extends RuntimeException {
        public PayloadConflictException(String providerRequestId) {
            super("Provider request payload conflicts with " + providerRequestId);
        }
    }
}
