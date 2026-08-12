package io.hoony.paymentconsumer;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class PaymentEventHandler {

    public static final String CONSUMER_GROUP = "payment-audit-v1";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PaymentEventHandler(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Result handle(PaymentEventEnvelope event) {
        if (event.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported payment event schema version: " + event.schemaVersion());
        }

        Instant now = Instant.now(clock);
        try {
            jdbc.update("""
                    INSERT INTO processed_events(consumer_group, event_id, processed_at)
                    VALUES (?, ?, ?)
                    """, CONSUMER_GROUP, event.eventId().toString(), Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            return Result.DUPLICATE;
        }

        List<Instant> progress = jdbc.query("""
                SELECT last_occurred_at
                  FROM consumer_aggregate_progress
                 WHERE consumer_group = ?
                   AND aggregate_id = ?
                 FOR UPDATE
                """, (rs, row) -> rs.getTimestamp(1).toInstant(),
                CONSUMER_GROUP, event.aggregateId().toString());

        if (!progress.isEmpty() && event.occurredAt().isBefore(progress.getFirst())) {
            return Result.OUT_OF_ORDER;
        }

        jdbc.update("""
                INSERT INTO payment_event_effects(
                    consumer_group, event_id, aggregate_id, event_type,
                    schema_version, occurred_at, processed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                CONSUMER_GROUP,
                event.eventId().toString(),
                event.aggregateId().toString(),
                event.eventType(),
                event.schemaVersion(),
                Timestamp.from(event.occurredAt()),
                Timestamp.from(now)
        );

        if (progress.isEmpty()) {
            jdbc.update("""
                    INSERT INTO consumer_aggregate_progress(
                        consumer_group, aggregate_id, last_occurred_at, last_event_id
                    ) VALUES (?, ?, ?, ?)
                    """, CONSUMER_GROUP, event.aggregateId().toString(),
                    Timestamp.from(event.occurredAt()), event.eventId().toString());
        } else {
            jdbc.update("""
                    UPDATE consumer_aggregate_progress
                       SET last_occurred_at = ?, last_event_id = ?
                     WHERE consumer_group = ?
                       AND aggregate_id = ?
                    """, Timestamp.from(event.occurredAt()), event.eventId().toString(),
                    CONSUMER_GROUP, event.aggregateId().toString());
        }
        return Result.PROCESSED;
    }

    public enum Result {
        PROCESSED,
        DUPLICATE,
        OUT_OF_ORDER
    }
}