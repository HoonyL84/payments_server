package io.hoony.payment.presentation.testsupport;

import io.hoony.payment.application.admission.ProviderCapacityGuard;
import io.hoony.payment.application.outbox.OutboxRelayService;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.config.RuntimeInstanceProperties;
import io.hoony.payment.infrastructure.pg.FakePaymentGateway;
import io.hoony.payment.infrastructure.pg.PgApproveStatus;
import io.hoony.payment.infrastructure.pg.PgConfirmApproveStatus;
import io.hoony.payment.infrastructure.pg.ProviderCallExecutor;
import io.hoony.payment.infrastructure.pg.ProviderOperation;
import io.hoony.payment.infrastructure.pg.ResilientPaymentGateway;
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
    private final RuntimeInstanceProperties runtime;
    private final ResilientPaymentGateway resilientGateway;
    private final ProviderCallExecutor providerCalls;
    private final ProviderCapacityGuard providerCapacity;
    private volatile double invalidTransitionBaseline;
    private volatile HotspotMetrics hotspotBaseline = HotspotMetrics.zero();
    private volatile ProviderMetrics providerBaseline = ProviderMetrics.zero();

    public K6TestSupportController(
            JdbcTemplate jdbc,
            FakePaymentGateway gateway,
            OutboxRelayService outboxRelay,
            MeterRegistry meterRegistry,
            StringRedisTemplate redis,
            RuntimeInstanceProperties runtime,
            ResilientPaymentGateway resilientGateway,
            ProviderCallExecutor providerCalls,
            ProviderCapacityGuard providerCapacity
    ) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.outboxRelay = outboxRelay;
        this.meterRegistry = meterRegistry;
        this.redis = redis;
        this.runtime = runtime;
        this.resilientGateway = resilientGateway;
        this.providerCalls = providerCalls;
        this.providerCapacity = providerCapacity;
    }

    @Transactional
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        jdbc.update("DELETE FROM payment_event_effects");
        jdbc.update("DELETE FROM consumer_aggregate_progress");
        jdbc.update("DELETE FROM processed_events");
        jdbc.update("DELETE FROM pg_webhook_receipts");
        jdbc.update("DELETE FROM ledger_entries");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM payment_attempts");
        jdbc.update("DELETE FROM payment_cancellations");
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM payments");
        gateway.reset();
        resilientGateway.resetProtection();
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        invalidTransitionBaseline = invalidTransitionCount();
        hotspotBaseline = hotspotSnapshot();
        providerBaseline = providerSnapshot();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/pg")
    public ResponseEntity<Void> pg(
            @RequestParam(defaultValue = "APPROVED") String approve,
            @RequestParam(defaultValue = "APPROVED") String confirmApprove,
            @RequestParam(defaultValue = "CANCELED") String cancel,
            @RequestParam(defaultValue = "CANCELED") String confirmCancel
    ) {
        gateway.nextApproveStatus(PgApproveStatus.valueOf(approve));
        gateway.nextConfirmApproveStatus(PgConfirmApproveStatus.valueOf(confirmApprove));
        gateway.nextCancellationStatus(PaymentGateway.CancellationStatus.valueOf(cancel));
        gateway.nextCancellationConfirmationStatus(
                PaymentGateway.CancellationConfirmationStatus.valueOf(confirmCancel)
        );
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/provider-protection/reset")
    public ResponseEntity<Void> resetProviderProtection() {
        resilientGateway.resetProtection();
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

    @PostMapping("/relay-outbox-once")
    public Map<String, Integer> relayOutboxOnce(@RequestParam int limit) {
        return Map.of("published", outboxRelay.relayPending(limit));
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
                "processedEvents", count("SELECT COUNT(*) FROM processed_events"),
                "paymentEventEffects", count("SELECT COUNT(*) FROM payment_event_effects"),
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

    @GetMapping("/multi-instance")
    public Map<String, Object> multiInstance() {
        HotspotMetrics current = hotspotSnapshot();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("instanceId", runtime.instanceId());
        report.put("approveCalls", gateway.approveCallCount());
        report.put("gateAcquired", roundedDelta(current.gateAcquired(), hotspotBaseline.gateAcquired()));
        report.put("gateRejected", roundedDelta(current.gateRejected(), hotspotBaseline.gateRejected()));
        report.put("gateUnavailable", roundedDelta(current.gateUnavailable(), hotspotBaseline.gateUnavailable()));
        report.put("dbTransactionCount", current.dbTransactionCount() - hotspotBaseline.dbTransactionCount());
        report.put("dbTransactionTotalMillis", current.dbTransactionTotalMillis() - hotspotBaseline.dbTransactionTotalMillis());
        report.put("lockWaitCount", current.lockWaitCount() - hotspotBaseline.lockWaitCount());
        report.put("lockWaitTotalMillis", current.lockWaitTotalMillis() - hotspotBaseline.lockWaitTotalMillis());
        report.put("outboxClaimed", roundedDelta(current.outboxClaimed(), hotspotBaseline.outboxClaimed()));
        report.put("outboxPublished", roundedDelta(current.outboxPublished(), hotspotBaseline.outboxPublished()));
        report.put("outboxClaimCount", current.outboxClaimCount() - hotspotBaseline.outboxClaimCount());
        report.put("outboxClaimTotalMillis", current.outboxClaimTotalMillis() - hotspotBaseline.outboxClaimTotalMillis());
        report.put("outboxTotal", count("SELECT COUNT(*) FROM outbox_events"));
        report.put("outboxPublishedRows", count("SELECT COUNT(*) FROM outbox_events WHERE status='PUBLISHED'"));
        report.put("outboxPendingRows", count("SELECT COUNT(*) FROM outbox_events WHERE status='PENDING'"));
        report.put("outboxClaimedRows", count("SELECT COUNT(*) FROM outbox_events WHERE claim_owner IS NOT NULL"));
        return Map.copyOf(report);
    }

    @GetMapping("/overload")
    public Map<String, Object> overload() {
        ProviderMetrics current = providerSnapshot();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("instanceId", runtime.instanceId());
        report.put("approveCalls", gateway.approveCallCount());
        report.put("confirmApproveCalls", gateway.confirmApproveCallCount());
        report.put("admissionAccepted", roundedDelta(
                current.admissionAccepted(), providerBaseline.admissionAccepted()
        ));
        report.put("admissionRejected", roundedDelta(
                current.admissionRejected(), providerBaseline.admissionRejected()
        ));
        report.put("retryAttempted", roundedDelta(
                current.retryAttempted(), providerBaseline.retryAttempted()
        ));
        report.put("retrySuppressed", roundedDelta(
                current.retrySuppressed(), providerBaseline.retrySuppressed()
        ));
        report.put("retryBudgetExhausted", roundedDelta(
                current.retryBudgetExhausted(), providerBaseline.retryBudgetExhausted()
        ));
        report.put("providerTimeouts", roundedDelta(
                current.providerTimeouts(), providerBaseline.providerTimeouts()
        ));
        report.put("circuitRejected", roundedDelta(
                current.circuitRejected(), providerBaseline.circuitRejected()
        ));
        report.put("bulkheadRejected", roundedDelta(
                current.bulkheadRejected(), providerBaseline.bulkheadRejected()
        ));
        report.put("commandCircuit", providerCalls.circuitState(ProviderOperation.APPROVE));
        report.put("confirmCircuit", providerCalls.circuitState(ProviderOperation.CONFIRM_APPROVE));
        report.put("commandInFlight", providerCapacity.commandInFlight());
        report.put("inquiryInFlight", providerCapacity.inquiryInFlight());
        report.put("commandExecutorActive", providerCalls.commandActive());
        report.put("inquiryExecutorActive", providerCalls.inquiryActive());
        report.put("inquiryQueued", providerCalls.inquiryQueued());
        report.put("maxInquiryQueueDepth", providerCalls.maxInquiryQueueDepth());
        report.put("retryInFlight", providerCalls.retryInFlight());
        report.put("maxRetryInFlight", providerCalls.maxRetryInFlight());
        report.put("payments", count("SELECT COUNT(*) FROM payments"));
        report.put("approvedPayments", count("SELECT COUNT(*) FROM payments WHERE state='APPROVED'"));
        report.put("pendingPayments", count(
                "SELECT COUNT(*) FROM payments WHERE state IN ('APPROVING', 'PENDING_CONFIRMATION', 'CONFIRMING')"
        ));
        report.put("processingIdempotency", count(
                "SELECT COUNT(*) FROM idempotency_records WHERE status='PROCESSING'"
        ));
        report.put("ledgerDrift", consistency().get("ledgerDrift"));
        report.put("pendingOutbox", count("SELECT COUNT(*) FROM outbox_events WHERE status='PENDING'"));
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
                timerTotalMillis("payments.db.lock.wait"),
                counterCount("payments.outbox.claimed"),
                counterCount("payments.outbox.publish", "outcome", "success"),
                timerCount("payments.outbox.claim.duration"),
                timerTotalMillis("payments.outbox.claim.duration")
        );
    }

    private ProviderMetrics providerSnapshot() {
        return new ProviderMetrics(
                counterCount("payments.provider.admission", "outcome", "accepted"),
                counterCount("payments.provider.admission", "outcome", "rejected"),
                counterCount("payments.provider.retries", "outcome", "attempted"),
                counterCount("payments.provider.retries", "outcome", "suppressed"),
                counterCount("payments.provider.retries", "outcome", "budget_exhausted"),
                counterCount("payments.provider.calls", "outcome", "timeout"),
                counterCount("payments.provider.calls", "outcome", "circuit_rejected"),
                counterCount("payments.provider.calls", "outcome", "bulkhead_rejected")
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

    private double counterCount(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counters().stream()
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

    private record ProviderMetrics(
            double admissionAccepted,
            double admissionRejected,
            double retryAttempted,
            double retrySuppressed,
            double retryBudgetExhausted,
            double providerTimeouts,
            double circuitRejected,
            double bulkheadRejected
    ) {
        private static ProviderMetrics zero() {
            return new ProviderMetrics(0, 0, 0, 0, 0, 0, 0, 0);
        }
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
            double lockWaitTotalMillis,
            double outboxClaimed,
            double outboxPublished,
            long outboxClaimCount,
            double outboxClaimTotalMillis
    ) {
        private static HotspotMetrics zero() {
            return new HotspotMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
