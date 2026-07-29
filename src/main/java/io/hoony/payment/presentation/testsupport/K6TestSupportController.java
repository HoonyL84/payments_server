package io.hoony.payment.presentation.testsupport;

import io.hoony.payment.application.outbox.OutboxRelayService;
import io.hoony.payment.infrastructure.pg.FakePaymentGateway;
import io.hoony.payment.infrastructure.pg.PgApproveStatus;
import io.hoony.payment.infrastructure.pg.PgConfirmApproveStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("k6")
@RestController
@RequestMapping("/internal/v1/test-support")
public class K6TestSupportController {
    private final JdbcTemplate jdbc;
    private final FakePaymentGateway gateway;
    private final OutboxRelayService outboxRelay;
    private final MeterRegistry meterRegistry;
    private volatile double invalidTransitionBaseline;

    public K6TestSupportController(
            JdbcTemplate jdbc,
            FakePaymentGateway gateway,
            OutboxRelayService outboxRelay,
            MeterRegistry meterRegistry
    ) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.outboxRelay = outboxRelay;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        jdbc.update("DELETE FROM ledger_entries");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM payment_cancellations");
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM payments");
        gateway.nextApproveStatus(PgApproveStatus.APPROVED);
        gateway.nextConfirmApproveStatus(PgConfirmApproveStatus.APPROVED);
        gateway.nextCancellationStatus(io.hoony.payment.application.port.out.PaymentGateway.CancellationStatus.CANCELED);
        invalidTransitionBaseline = invalidTransitionCount();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pg")
    public ResponseEntity<Void> pg(@RequestParam String approve) {
        gateway.nextApproveStatus(PgApproveStatus.valueOf(approve));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/relay-outbox")
    public Map<String, Integer> relayOutbox() {
        int total = 0;
        int published;
        do {
            published = outboxRelay.relayPending(100);
            total += published;
        } while (published == 100);
        return Map.of("published", total);
    }

    @GetMapping("/consistency")
    public Map<String, Long> consistency() {
        return Map.of(
                "duplicatePayments", count("SELECT COUNT(*) FROM (SELECT 1 FROM payments GROUP BY merchant_id, order_id HAVING COUNT(*) > 1) d"),
                "duplicateCancellations", count("SELECT COUNT(*) FROM (SELECT cancellation_id FROM payment_attempts WHERE operation='CANCEL' AND cancellation_id IS NOT NULL GROUP BY cancellation_id HAVING COUNT(*) > 1) d"),
                "ledgerDrift", count("SELECT COUNT(*) FROM (SELECT transaction_group_id, currency FROM ledger_entries GROUP BY transaction_group_id, currency HAVING SUM(CASE WHEN direction='DEBIT' THEN amount_minor_units ELSE 0 END) <> SUM(CASE WHEN direction='CREDIT' THEN amount_minor_units ELSE 0 END)) d"),
                "processingIdempotency", count("SELECT COUNT(*) FROM idempotency_records WHERE status='PROCESSING'"),
                "pendingConfirmations", count("SELECT (SELECT COUNT(*) FROM payments WHERE state IN ('PENDING_CONFIRMATION', 'CONFIRMING')) + (SELECT COUNT(*) FROM payment_cancellations WHERE state IN ('CANCEL_PENDING_CONFIRMATION', 'CANCEL_CONFIRMING'))"),
                "pendingOutbox", count("SELECT COUNT(*) FROM outbox_events WHERE status='PENDING'"),
                "invalidTransitions", Math.round(Math.max(0, invalidTransitionCount() - invalidTransitionBaseline))
        );
    }

    private double invalidTransitionCount() {
        Counter counter = meterRegistry.find("payments.state.invalid.transitions").counter();
        return counter == null ? 0 : counter.count();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
