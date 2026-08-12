package io.hoony.reconciliation;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

final class ReconciliationWriter implements ItemWriter<ReconciliationModel.Result> {

    private final JdbcTemplate jdbc;
    private final String runKey;
    private final Clock clock;

    ReconciliationWriter(JdbcTemplate jdbc, String runKey, Clock clock) {
        this.jdbc = jdbc;
        this.runKey = runKey;
        this.clock = clock;
    }

    @Override
    public void write(Chunk<? extends ReconciliationModel.Result> chunk) {
        Instant now = Instant.now(clock);
        for (ReconciliationModel.Result result : chunk) {
            for (ReconciliationModel.Finding finding : result.findings()) {
                insertCase(result.paymentId(), finding, now);
                if (finding.correctionCommand() != null) {
                    insertCorrection(result.paymentId(), finding, now);
                }
            }
        }
    }

    private void insertCase(String paymentId, ReconciliationModel.Finding finding, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO reconciliation_cases(
                        run_key, payment_id, case_type, classification,
                        expected_value, actual_value, detail, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    runKey,
                    paymentId,
                    finding.type().name(),
                    finding.classification().name(),
                    finding.expected(),
                    finding.actual(),
                    finding.detail(),
                    Timestamp.from(now)
            );
        } catch (DuplicateKeyException ignored) {
            // A restarted chunk may write the same classification again.
        }
    }

    private void insertCorrection(String paymentId, ReconciliationModel.Finding finding, Instant now) {
        String correctionKey = finding.correctionCommand() + ":" + paymentId;
        try {
            jdbc.update("""
                    INSERT INTO reconciliation_corrections(
                        correction_key, payment_id, command_type,
                        source_run_key, status, requested_at
                    ) VALUES (?, ?, ?, ?, 'REQUESTED', ?)
                    """, correctionKey, paymentId, finding.correctionCommand(), runKey, Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // The correction command is globally idempotent.
        }
    }
}