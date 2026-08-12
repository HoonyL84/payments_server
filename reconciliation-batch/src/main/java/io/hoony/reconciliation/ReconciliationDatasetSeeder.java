package io.hoony.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "reconciliation.dataset-enabled", havingValue = "true")
class ReconciliationDatasetSeeder {

    private static final String FIXTURE_MERCHANT = "reconciliation-fixture";
    private static final int BATCH_SIZE = 1_000;

    private final JdbcTemplate jdbc;
    private final RestClient pg;

    ReconciliationDatasetSeeder(
            JdbcTemplate jdbc,
            RestClient.Builder builder,
            ReconciliationProperties properties
    ) {
        this.jdbc = jdbc;
        this.pg = builder.baseUrl(properties.mockPgBaseUrl().toString()).build();
    }

    DatasetResult seed(int size) {
        if (size < 1 || size > 200_000) {
            throw new IllegalArgumentException("Dataset size must be between 1 and 200000.");
        }
        requireIsolatedDatabase();
        clearFixture();

        pg.post()
                .uri(uri -> uri.path("/internal/v1/test-support/dataset")
                        .queryParam("size", size)
                        .build())
                .retrieve()
                .toBodilessEntity();

        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO merchants(id, status, pg_provider, routing_key, created_at)
                VALUES (?, 'ACTIVE', 'MOCK', 'reconciliation', ?)
                ON DUPLICATE KEY UPDATE status = 'ACTIVE'
                """, FIXTURE_MERCHANT, Timestamp.from(now));

        for (int start = 1; start <= size; start += BATCH_SIZE) {
            int end = Math.min(size, start + BATCH_SIZE - 1);
            List<Integer> indexes = new ArrayList<>(end - start + 1);
            for (int index = start; index <= end; index++) {
                indexes.add(index);
            }
            insertPayments(indexes, now);
            insertAttempts(indexes, now);
            insertLedger(indexes, now);
            insertOutbox(indexes, now);
            insertConsumerEffects(indexes, now);
        }

        long completeGroups = size / 100;
        return new DatasetResult(
                size,
                completeGroups * 2,
                completeGroups,
                completeGroups * 2,
                completeGroups * 2
        );
    }

    private void requireIsolatedDatabase() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!"payments_reconciliation".equals(database)) {
            throw new IllegalStateException("Dataset seeding is allowed only in payments_reconciliation.");
        }
    }

    private void clearFixture() {
        jdbc.update("""
                DELETE effects
                  FROM payment_event_effects effects
                  JOIN payments payment ON payment.id = effects.aggregate_id
                 WHERE payment.merchant_id = ?
                """, FIXTURE_MERCHANT);
        jdbc.update("""
                DELETE processed
                  FROM processed_events processed
                  JOIN outbox_events event ON event.id = processed.event_id
                  JOIN payments payment ON payment.id = event.aggregate_id
                 WHERE payment.merchant_id = ?
                """, FIXTURE_MERCHANT);
        jdbc.update("""
                DELETE progress
                  FROM consumer_aggregate_progress progress
                  JOIN payments payment ON payment.id = progress.aggregate_id
                 WHERE payment.merchant_id = ?
                """, FIXTURE_MERCHANT);
        jdbc.update("""
                DELETE entry
                  FROM ledger_entries entry
                  JOIN payments payment ON payment.id = entry.payment_id
                 WHERE payment.merchant_id = ?
                """, FIXTURE_MERCHANT);
        jdbc.update("""
                DELETE event
                  FROM outbox_events event
                  JOIN payments payment ON payment.id = event.aggregate_id
                 WHERE payment.merchant_id = ?
                """, FIXTURE_MERCHANT);
        jdbc.update("""
                DELETE attempt
                  FROM payment_attempts attempt
                  JOIN payments payment ON payment.id = attempt.payment_id
                 WHERE payment.merchant_id = ?
                """, FIXTURE_MERCHANT);
        jdbc.update("""
                DELETE cancellation
                  FROM payment_cancellations cancellation
                  JOIN payments payment ON payment.id = cancellation.payment_id
                 WHERE payment.merchant_id = ?
                """, FIXTURE_MERCHANT);
        jdbc.update("DELETE FROM payments WHERE merchant_id = ?", FIXTURE_MERCHANT);
        jdbc.update("DELETE FROM reconciliation_run_summaries");
        jdbc.update("DELETE FROM reconciliation_corrections");
        jdbc.update("DELETE FROM reconciliation_cases");
        jdbc.update("DELETE FROM reconciliation_pg_snapshots");
        jdbc.update("DELETE FROM reconciliation_failure_markers");
    }

    private void insertPayments(List<Integer> indexes, Instant now) {
        jdbc.batchUpdate("""
                INSERT INTO payments(
                    id, user_id, merchant_id, order_id, amount_minor_units, currency,
                    state, canceled_amount_minor_units, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 1000, 'KRW', ?, 0, 0, ?, ?)
                """, indexes, BATCH_SIZE, (statement, index) -> {
            statement.setString(1, paymentId(index));
            statement.setString(2, "batch-user-" + index);
            statement.setString(3, FIXTURE_MERCHANT);
            statement.setString(4, "batch-order-" + index);
            statement.setString(5, index % 100 == 4 ? "PENDING_CONFIRMATION" : "APPROVED");
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setTimestamp(7, Timestamp.from(now));
        });
    }

    private void insertAttempts(List<Integer> indexes, Instant now) {
        jdbc.batchUpdate("""
                INSERT INTO payment_attempts(
                    id, payment_id, cancellation_id, operation, provider,
                    provider_request_id, provider_transaction_id, result,
                    error_code, started_at, completed_at
                ) VALUES (?, ?, NULL, 'APPROVE', 'MOCK', ?, ?, 'SUCCEEDED', NULL, ?, ?)
                """, indexes, BATCH_SIZE, (statement, index) -> {
            statement.setString(1, namedId("attempt-" + index));
            statement.setString(2, paymentId(index));
            statement.setString(3, providerRequestId(index));
            statement.setString(4, "mock-pg-batch-" + index);
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setTimestamp(6, Timestamp.from(now));
        });
    }

    private void insertLedger(List<Integer> indexes, Instant now) {
        List<Integer> balanced = indexes.stream()
                .filter(index -> index % 100 != 1 && index % 100 != 4)
                .toList();
        insertLedgerSide(balanced, now, "PG_CLEARING", "DEBIT", "ledger-debit-");
        insertLedgerSide(balanced, now, "MERCHANT_PAYABLE", "CREDIT", "ledger-credit-");
    }

    private void insertLedgerSide(
            List<Integer> indexes,
            Instant now,
            String account,
            String direction,
            String idPrefix
    ) {
        jdbc.batchUpdate("""
                INSERT INTO ledger_entries(
                    id, transaction_group_id, payment_id, cancellation_id,
                    entry_type, account, direction, amount_minor_units, currency, recorded_at
                ) VALUES (?, ?, ?, NULL, 'APPROVAL', ?, ?, 1000, 'KRW', ?)
                """, indexes, BATCH_SIZE, (statement, index) -> {
            statement.setString(1, namedId(idPrefix + index));
            statement.setString(2, namedId("ledger-group-" + index));
            statement.setString(3, paymentId(index));
            statement.setString(4, account);
            statement.setString(5, direction);
            statement.setTimestamp(6, Timestamp.from(now));
        });
    }

    private void insertOutbox(List<Integer> indexes, Instant now) {
        jdbc.batchUpdate("""
                INSERT INTO outbox_events(
                    id, aggregate_type, aggregate_id, event_type, payload,
                    status, publish_attempts, claim_owner, claimed_until, created_at, published_at
                ) VALUES (?, 'PAYMENT', ?, ?, ?, ?, 1, NULL, NULL, ?, ?)
                """, indexes, BATCH_SIZE, (statement, index) -> {
            boolean pending = index % 100 == 2;
            boolean paymentPending = index % 100 == 4;
            statement.setString(1, outboxId(index));
            statement.setString(2, paymentId(index));
            statement.setString(3, paymentPending ? "PAYMENT_PENDING_CONFIRMATION" : "PAYMENT_APPROVED");
            statement.setString(4, "{\"paymentId\":\"" + paymentId(index) + "\"}");
            statement.setString(5, pending ? "PENDING" : "PUBLISHED");
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setTimestamp(7, pending ? null : Timestamp.from(now));
        });
    }

    private void insertConsumerEffects(List<Integer> indexes, Instant now) {
        List<Integer> consumed = indexes.stream()
                .filter(index -> index % 100 != 2 && index % 100 != 3)
                .toList();
        jdbc.batchUpdate("""
                INSERT INTO processed_events(consumer_group, event_id, processed_at)
                VALUES ('payment-audit-v1', ?, ?)
                """, consumed, BATCH_SIZE, (statement, index) -> {
            statement.setString(1, outboxId(index));
            statement.setTimestamp(2, Timestamp.from(now));
        });
        jdbc.batchUpdate("""
                INSERT INTO payment_event_effects(
                    consumer_group, event_id, aggregate_id, event_type,
                    schema_version, occurred_at, processed_at
                ) VALUES ('payment-audit-v1', ?, ?, ?, 1, ?, ?)
                """, consumed, BATCH_SIZE, (statement, index) -> {
            statement.setString(1, outboxId(index));
            statement.setString(2, paymentId(index));
            statement.setString(3, index % 100 == 4
                    ? "PAYMENT_PENDING_CONFIRMATION"
                    : "PAYMENT_APPROVED");
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
        });
    }

    static String paymentId(int index) {
        return new UUID(0L, index).toString();
    }

    static String providerRequestId(int index) {
        return "batch-approve-" + index;
    }

    private static String outboxId(int index) {
        return namedId("outbox-" + index);
    }

    private static String namedId(String source) {
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    record DatasetResult(
            int size,
            long expectedAutoCorrect,
            long expectedRequery,
            long expectedManualReview,
            long expectedCorrections
    ) {
    }
}