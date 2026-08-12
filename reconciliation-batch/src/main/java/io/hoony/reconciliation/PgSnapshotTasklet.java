package io.hoony.reconciliation;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class PgSnapshotTasklet implements Tasklet {

    private final JdbcTemplate jdbc;
    private final RestClient pg;
    private final String runKey;
    private final Instant from;
    private final int pageSize;

    public PgSnapshotTasklet(
            JdbcTemplate jdbc,
            RestClient.Builder builder,
            ReconciliationProperties properties,
            String runKey,
            Instant from
    ) {
        this.jdbc = jdbc;
        this.pg = builder.baseUrl(properties.mockPgBaseUrl().toString()).build();
        this.runKey = runKey;
        this.from = from;
        this.pageSize = properties.pageSize();
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        jdbc.update("DELETE FROM reconciliation_pg_snapshots WHERE run_key = ?", runKey);

        String cursor = null;
        int total = 0;
        do {
            String currentCursor = cursor;
            Page page = pg.get()
                    .uri(uri -> {
                        var builder = uri.path("/api/v1/transactions")
                                .queryParam("from", from)
                                .queryParam("limit", pageSize);
                        if (currentCursor != null) {
                            builder.queryParam("cursor", currentCursor);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .body(Page.class);
            if (page == null || page.items() == null) {
                throw new IllegalStateException("Mock PG returned an empty transaction page.");
            }
            write(page.items());
            total += page.items().size();
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());

        contribution.incrementWriteCount(total);
        chunkContext.getStepContext().getStepExecution().getJobExecution()
                .getExecutionContext().putInt("pgSnapshotCount", total);
        return RepeatStatus.FINISHED;
    }

    private void write(List<Transaction> items) {
        jdbc.batchUpdate("""
                INSERT INTO reconciliation_pg_snapshots(
                    run_key, provider_request_id, payment_id, cancellation_id,
                    operation, status, provider_transaction_id, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, items, pageSize, (statement, item) -> {
            statement.setString(1, runKey);
            statement.setString(2, item.providerRequestId());
            statement.setString(3, item.paymentId().toString());
            statement.setString(4, item.cancellationId() == null ? null : item.cancellationId().toString());
            statement.setString(5, item.operation());
            statement.setString(6, item.status());
            statement.setString(7, item.providerTransactionId());
            statement.setTimestamp(8, Timestamp.from(item.createdAt()));
        });
    }

    record Page(List<Transaction> items, String nextCursor) {
    }

    record Transaction(
            String providerRequestId,
            String operation,
            String status,
            String providerTransactionId,
            Instant createdAt,
            UUID paymentId,
            UUID cancellationId
    ) {
    }
}