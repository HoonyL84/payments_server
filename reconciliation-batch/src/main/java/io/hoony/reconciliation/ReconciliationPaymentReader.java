package io.hoony.reconciliation;

import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

final class ReconciliationPaymentReader implements ItemStreamReader<ReconciliationModel.PaymentRow> {

    private static final String CHECKPOINT_KEY = "reconciliation.lastPaymentId";

    private final JdbcTemplate jdbc;
    private final String runKey;
    private final int pageSize;

    private String lastPaymentId = "";
    private Iterator<ReconciliationModel.PaymentRow> page = Collections.emptyIterator();

    ReconciliationPaymentReader(JdbcTemplate jdbc, String runKey, int pageSize) {
        this.jdbc = jdbc;
        this.runKey = runKey;
        this.pageSize = pageSize;
    }

    @Override
    public ReconciliationModel.PaymentRow read() {
        if (!page.hasNext()) {
            List<ReconciliationModel.PaymentRow> rows = fetch();
            if (rows.isEmpty()) {
                return null;
            }
            page = rows.iterator();
        }

        ReconciliationModel.PaymentRow row = page.next();
        lastPaymentId = row.paymentId();
        return row;
    }

    private List<ReconciliationModel.PaymentRow> fetch() {
        return jdbc.query("""
                SELECT p.id,
                       p.state,
                       p.amount_minor_units,
                       p.canceled_amount_minor_units,
                       COALESCE((
                           SELECT s.status
                             FROM reconciliation_pg_snapshots s
                            WHERE s.run_key = ?
                              AND s.payment_id = p.id
                              AND s.operation = 'APPROVE'
                            ORDER BY s.occurred_at DESC, s.provider_request_id DESC
                            LIMIT 1
                       ), 'MISSING') AS pg_approval_status,
                       COALESCE((
                           SELECT SUM(c.amount_minor_units)
                             FROM payment_cancellations c
                            WHERE c.payment_id = p.id
                              AND c.state = 'CANCELED'
                       ), 0) AS successful_cancellation_amount,
                       COALESCE((
                           SELECT SUM(c.amount_minor_units)
                             FROM payment_cancellations c
                             JOIN reconciliation_pg_snapshots s
                               ON s.run_key = ?
                              AND s.cancellation_id = c.id
                              AND s.operation = 'CANCEL'
                              AND s.status = 'CANCELED'
                            WHERE c.payment_id = p.id
                       ), 0) AS pg_canceled_amount,
                       COALESCE((
                           SELECT SUM(l.amount_minor_units)
                             FROM ledger_entries l
                            WHERE l.payment_id = p.id
                              AND l.entry_type = 'APPROVAL'
                              AND l.direction = 'DEBIT'
                       ), 0) AS approval_debit,
                       COALESCE((
                           SELECT SUM(l.amount_minor_units)
                             FROM ledger_entries l
                            WHERE l.payment_id = p.id
                              AND l.entry_type = 'APPROVAL'
                              AND l.direction = 'CREDIT'
                       ), 0) AS approval_credit,
                       COALESCE((
                           SELECT SUM(l.amount_minor_units)
                             FROM ledger_entries l
                            WHERE l.payment_id = p.id
                              AND l.entry_type = 'CANCELLATION'
                              AND l.direction = 'DEBIT'
                       ), 0) AS cancellation_debit,
                       COALESCE((
                           SELECT SUM(l.amount_minor_units)
                             FROM ledger_entries l
                            WHERE l.payment_id = p.id
                              AND l.entry_type = 'CANCELLATION'
                              AND l.direction = 'CREDIT'
                       ), 0) AS cancellation_credit,
                       (SELECT COUNT(*) FROM outbox_events o WHERE o.aggregate_id = p.id) AS outbox_count,
                       (SELECT COUNT(*) FROM outbox_events o WHERE o.aggregate_id = p.id AND o.status = 'PENDING') AS pending_outbox_count,
                       (SELECT COUNT(*) FROM outbox_events o WHERE o.aggregate_id = p.id AND o.status = 'PUBLISHED') AS published_outbox_count,
                       (SELECT COUNT(*) FROM payment_event_effects e WHERE e.aggregate_id = p.id) AS consumer_effect_count
                  FROM payments p
                 WHERE p.id > ?
                 ORDER BY p.id
                 LIMIT ?
                """, (resultSet, rowNum) -> new ReconciliationModel.PaymentRow(
                resultSet.getString("id"),
                resultSet.getString("state"),
                resultSet.getLong("amount_minor_units"),
                resultSet.getLong("canceled_amount_minor_units"),
                resultSet.getString("pg_approval_status"),
                resultSet.getLong("successful_cancellation_amount"),
                resultSet.getLong("pg_canceled_amount"),
                resultSet.getLong("approval_debit"),
                resultSet.getLong("approval_credit"),
                resultSet.getLong("cancellation_debit"),
                resultSet.getLong("cancellation_credit"),
                resultSet.getLong("outbox_count"),
                resultSet.getLong("pending_outbox_count"),
                resultSet.getLong("published_outbox_count"),
                resultSet.getLong("consumer_effect_count")
        ), runKey, runKey, lastPaymentId, pageSize);
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        lastPaymentId = executionContext.getString(CHECKPOINT_KEY, "");
        page = Collections.emptyIterator();
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putString(CHECKPOINT_KEY, lastPaymentId);
    }

    @Override
    public void close() throws ItemStreamException {
        page = Collections.emptyIterator();
    }
}