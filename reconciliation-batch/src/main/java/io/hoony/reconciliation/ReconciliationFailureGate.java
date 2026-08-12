package io.hoony.reconciliation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

@Component
class ReconciliationFailureGate {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    ReconciliationFailureGate(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    boolean armOnce(String runKey) {
        Boolean armed = requiresNew.execute(status -> {
            try {
                jdbc.update("""
                        INSERT INTO reconciliation_failure_markers(run_key, failure_point, created_at)
                        VALUES (?, 'RECONCILE_CHUNK', ?)
                        """, runKey, Timestamp.from(Instant.now()));
                return true;
            } catch (DuplicateKeyException exception) {
                return false;
            }
        });
        return Boolean.TRUE.equals(armed);
    }
}