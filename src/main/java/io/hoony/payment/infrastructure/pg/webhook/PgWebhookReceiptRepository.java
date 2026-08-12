package io.hoony.payment.infrastructure.pg.webhook;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public class PgWebhookReceiptRepository {

    private final JdbcTemplate jdbc;

    public PgWebhookReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID eventId, String providerRequestId, String operation, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO pg_webhook_receipts(
                        event_id, provider_request_id, operation, status, claimed_until, received_at, updated_at
                    ) VALUES (?, ?, ?, 'PROCESSING', ?, ?, ?)
                    """, eventId.toString(), providerRequestId, operation, now.plusSeconds(30), now, now);
            return true;
        } catch (DuplicateKeyException exception) {
            return jdbc.update("""
                    UPDATE pg_webhook_receipts
                       SET status = 'PROCESSING', claimed_until = ?, updated_at = ?
                     WHERE event_id = ?
                       AND status <> 'PROCESSED'
                       AND claimed_until < ?
                    """, now.plusSeconds(30), now, eventId.toString(), now) == 1;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(UUID eventId, Instant now) {
        jdbc.update("""
                UPDATE pg_webhook_receipts
                   SET status = 'PROCESSED', claimed_until = NULL, updated_at = ?
                 WHERE event_id = ?
                """, now, eventId.toString());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID eventId) {
        jdbc.update("DELETE FROM pg_webhook_receipts WHERE event_id = ? AND status = 'PROCESSING'",
                eventId.toString());
    }
}