package io.hoony.payment.presentation.testsupport;

import io.hoony.payment.application.outbox.OutboxRelayService;
import io.hoony.payment.infrastructure.pg.FakePaymentGateway;
import io.hoony.payment.infrastructure.pg.PgApproveStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final StringRedisTemplate redis;
    private volatile double invalidTransitionBaseline;
    private volatile HotspotMetrics hotspotBaseline = HotspotMetrics.zero();

    public K6TestSupportController(
            JdbcTemplate jdbc,
            FakePaymentGateway gateway,
            OutboxRelayService outboxRelay,
            MeterRegistry meterRegistry,
            StringRedisTemplate redis
    ) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.outboxRelay = outboxRelay;
        this.meterRegistry = meterRegistry;
        this.redis = redis;
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
        gateway.reset();
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        invalidTransitionBaseline = invalidTransitionCount();
        hotspotBaseline = hotspotSnapshot();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pg")
    public ResponseEntity<Void> pg(@RequestParam String approve) {
        gateway.nextApproveStatus(PgApproveStatus.valueOf(approve));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pg-delay")
    public ResponseEntity<Void> pgDelay(@RequestParam long millis) {
        gateway.responseDelay(Duration.ofMillis(millis));
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

    @GetMapping("/hotspot")
    public Map<String, Number> hotspot() {
        HotspotMetrics current = hotspotSnapshot();
        Map<String, Number> report = new LinkedHashMap<>();
        report.put("approveCalls", gateway.approveCallCount());
        report.put("cancelCalls", gateway.cancelCallCount());
        report.put("approvalAttempts", count("SELECT COUNT(*) FROM payment_attempts WHERE operation='APPROVE'"));
        report.put("cancellationAttempts", count("SELECT COUNT(*) FROM payment_attempts WHERE operation='CANCEL'"));
        report.put("gateAcquired", roundedDelta(current.gateAcquired(), hotspotBaseline.gateAcquired()));
        report.put("gateRejected", roundedDelta(current.gateRejected(), hotspotBaseline.gateRejected()));
        report.put("gateConflicts", roundedDelta(current.gateConflicts(), hotspotBaseline.gateConflicts()));
        report.put("gateBypassed", roundedDelta(current.gateBypassed(), hotspotBaseline.gateBypassed()));
        report.put("gateUnavailable", roundedDelta(current.gateUnavailable(), hotspotBaseline.gateUnavailable()));
        report.put("dbTransactionCount", current.dbTransactionCount() - hotspotBaseline.dbTransactionCount());
        report.put("dbTransactionTotalMillis", current.dbTransactionTotalMillis() - hotspotBaseline.dbTransactionTotalMillis());
        report.put("lockWaitCount", current.lockWaitCount() - hotspotBaseline.lockWaitCount());
        report.put("lockWaitTotalMillis", current.lockWaitTotalMillis() - hotspotBaseline.lockWaitTotalMillis());
        return Map.copyOf(report);
    }

    private double invalidTransitionCount() {
        Counter counter = meterRegistry.find("payments.state.invalid.transitions").counter();
        return counter == null ? 0 : counter.count();
    }

    private HotspotMetrics hotspotSnapshot() {
        return new HotspotMetrics(
                gateCount("acquired"),
                gateCount("rejected"),
                gateCount("conflict"),
                gateCount("bypassed") + gateCount("race_bypass"),
                gateCount("unavailable"),
                timerCount("payments.db.transaction.duration"),
                timerTotalMillis("payments.db.transaction.duration"),
                timerCount("payments.db.lock.wait"),
                timerTotalMillis("payments.db.lock.wait")
        );
    }

    private double gateCount(String outcome) {
        return meterRegistry.find("payments.idempotency.gate")
                .tag("outcome", outcome)
                .counters()
                .stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private long timerCount(String name) {
        return meterRegistry.find(name).timers().stream().mapToLong(Timer::count).sum();
    }

    private double timerTotalMillis(String name) {
        return meterRegistry.find(name).timers().stream()
                .mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS))
                .sum();
    }

    private long roundedDelta(double current, double baseline) {
        return Math.round(Math.max(0, current - baseline));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private record HotspotMetrics(
            double gateAcquired,
            double gateRejected,
            double gateConflicts,
            double gateBypassed,
            double gateUnavailable,
            long dbTransactionCount,
            double dbTransactionTotalMillis,
            long lockWaitCount,
            double lockWaitTotalMillis
    ) {
        private static HotspotMetrics zero() {
            return new HotspotMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
