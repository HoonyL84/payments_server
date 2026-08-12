package io.hoony.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class ReconciliationSummaryTasklet implements Tasklet {

    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final String runKey;

    public ReconciliationSummaryTasklet(
            JdbcTemplate jdbc,
            MeterRegistry metrics,
            Clock clock,
            String runKey
    ) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.clock = clock;
        this.runKey = runKey;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        var execution = chunkContext.getStepContext().getStepExecution().getJobExecution();
        RunStats stats = loadRunStats(execution.getJobInstance().getInstanceId());
        Instant finishedAt = Instant.now(clock);
        Duration duration = Duration.between(stats.startedAt(), finishedAt);

        long auto = count("AUTO_CORRECT");
        long requery = count("REQUERY_REQUIRED");
        long manual = count("MANUAL_REVIEW");

        jdbc.update("""
                INSERT INTO reconciliation_run_summaries(
                    run_key, job_execution_id, status, read_count, write_count,
                    auto_correct_count, requery_count, manual_review_count,
                    started_at, finished_at, duration_millis
                ) VALUES (?, ?, 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runKey,
                execution.getId(),
                stats.readCount(),
                stats.writeCount(),
                auto,
                requery,
                manual,
                Timestamp.from(stats.startedAt()),
                Timestamp.from(finishedAt),
                duration.toMillis()
        );

        recordMetrics("auto_correct", auto);
        recordMetrics("requery_required", requery);
        recordMetrics("manual_review", manual);
        metrics.timer("payments.reconciliation.duration").record(duration);
        return RepeatStatus.FINISHED;
    }

    private RunStats loadRunStats(long jobInstanceId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(step.READ_COUNT), 0) AS read_count,
                       COALESCE(SUM(step.WRITE_COUNT), 0) AS write_count,
                       MIN(execution.START_TIME) AS started_at
                  FROM BATCH_STEP_EXECUTION step
                  JOIN BATCH_JOB_EXECUTION execution
                    ON execution.JOB_EXECUTION_ID = step.JOB_EXECUTION_ID
                 WHERE execution.JOB_INSTANCE_ID = ?
                   AND step.STEP_NAME = 'paymentReconciliationStep'
                """, (resultSet, rowNum) -> new RunStats(
                resultSet.getLong("read_count"),
                resultSet.getLong("write_count"),
                resultSet.getTimestamp("started_at").toInstant()
        ), jobInstanceId);
    }

    private long count(String classification) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM reconciliation_cases
                 WHERE run_key = ?
                   AND classification = ?
                """, Long.class, runKey, classification);
        return count == null ? 0 : count;
    }

    private void recordMetrics(String classification, long count) {
        metrics.counter("payments.reconciliation.cases", "classification", classification)
                .increment(count);
    }

    private record RunStats(long readCount, long writeCount, Instant startedAt) {
    }
}