package io.hoony.payment.application.recovery;

import io.hoony.payment.application.cancellation.ConfirmCancellationService;
import io.hoony.payment.application.confirmation.ConfirmPaymentService;
import io.hoony.payment.application.ledger.LedgerConsistencyService;
import io.hoony.payment.application.outbox.OutboxRelayService;
import io.hoony.payment.application.port.out.CancellationRepository;
import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.cancellation.CancellationState;
import io.hoony.payment.domain.payment.PaymentState;
import io.hoony.payment.observability.PaymentMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RecoveryService {
    private static final int SCAN_LIMIT = 500;

    private final PaymentRepository payments;
    private final CancellationRepository cancellations;
    private final OutboxEventRepository outboxEvents;
    private final LedgerConsistencyService ledgerConsistency;
    private final ConfirmPaymentService confirmPayment;
    private final ConfirmCancellationService confirmCancellation;
    private final OutboxRelayService outboxRelay;
    private final PaymentMetrics metrics;
    private final Clock clock;

    public RecoveryService(
            PaymentRepository payments,
            CancellationRepository cancellations,
            OutboxEventRepository outboxEvents,
            LedgerConsistencyService ledgerConsistency,
            ConfirmPaymentService confirmPayment,
            ConfirmCancellationService confirmCancellation,
            OutboxRelayService outboxRelay,
            PaymentMetrics metrics,
            Clock clock
    ) {
        this.payments = payments;
        this.cancellations = cancellations;
        this.outboxEvents = outboxEvents;
        this.ledgerConsistency = ledgerConsistency;
        this.confirmPayment = confirmPayment;
        this.confirmCancellation = confirmCancellation;
        this.outboxRelay = outboxRelay;
        this.metrics = metrics;
        this.clock = clock;
    }

    public RecoveryReport inspect(Duration staleAfter) {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(requirePositive(staleAfter));
        List<RecoveryCandidate> candidates = new ArrayList<>();

        payments.findStaleProcessing(cutoff, SCAN_LIMIT).forEach(stale -> candidates.add(paymentCandidate(stale)));
        cancellations.findStaleProcessing(cutoff, SCAN_LIMIT)
                .forEach(stale -> candidates.add(cancellationCandidate(stale)));
        outboxEvents.findPendingBefore(cutoff, SCAN_LIMIT).forEach(event -> candidates.add(
                new RecoveryCandidate("OUTBOX", event.id().toString(), RecoveryAction.RELAY_OUTBOX,
                        "Outbox event has not been published.", event.createdAt())
        ));

        long ledgerDriftCount = ledgerConsistency.countDrifts();
        if (ledgerDriftCount > 0) {
            candidates.add(new RecoveryCandidate("LEDGER", "drift", RecoveryAction.MANUAL_REVIEW,
                    "Debit and credit totals do not match.", now));
        }
        int manual = (int) candidates.stream().filter(it -> it.action() == RecoveryAction.MANUAL_REVIEW).count();
        return new RecoveryReport(now, candidates.size() - manual, manual, ledgerDriftCount, List.copyOf(candidates));
    }

    public RecoveryRunResult recover(Duration staleAfter) {
        RecoveryReport report = inspect(staleAfter);
        int recovered = 0;
        List<String> failures = new ArrayList<>();
        boolean relayRequired = false;

        for (RecoveryCandidate candidate : report.candidates()) {
            try {
                switch (candidate.action()) {
                    case CONFIRM_PAYMENT -> confirmPayment.confirm(java.util.UUID.fromString(candidate.resourceId()));
                    case CONFIRM_CANCELLATION -> {
                        String[] ids = candidate.resourceId().split(":");
                        confirmCancellation.confirm(java.util.UUID.fromString(ids[0]), java.util.UUID.fromString(ids[1]));
                    }
                    case RELAY_OUTBOX -> {
                        relayRequired = true;
                        continue;
                    }
                    case MANUAL_REVIEW -> {
                        metrics.recovery(candidate.action().name(), "manual_review",
                                Duration.between(candidate.detectedFrom(), report.generatedAt()));
                        continue;
                    }
                }
                recovered++;
                metrics.recovery(candidate.action().name(), "recovered",
                        Duration.between(candidate.detectedFrom(), report.generatedAt()));
            } catch (RuntimeException exception) {
                metrics.recovery(candidate.action().name(), "failed",
                        Duration.between(candidate.detectedFrom(), report.generatedAt()));
                failures.add(candidate.resourceId() + ": " + exception.getMessage());
            }
        }
        if (relayRequired) {
            recovered += relayAll();
        }
        return new RecoveryRunResult(recovered, report.manualReviewCount(), List.copyOf(failures));
    }

    private int relayAll() {
        int total = 0;
        int published;
        do {
            published = outboxRelay.relayPending(100);
            total += published;
        } while (published == 100);
        return total;
    }

    private RecoveryCandidate paymentCandidate(StalePayment stale) {
        RecoveryAction action = stale.state() == PaymentState.PENDING_CONFIRMATION
                ? RecoveryAction.CONFIRM_PAYMENT : RecoveryAction.MANUAL_REVIEW;
        return new RecoveryCandidate("PAYMENT", stale.paymentId().toString(), action,
                "Payment remains in " + stale.state() + ".", stale.updatedAt());
    }

    private RecoveryCandidate cancellationCandidate(StaleCancellation stale) {
        RecoveryAction action = stale.state() == CancellationState.CANCEL_PENDING_CONFIRMATION
                ? RecoveryAction.CONFIRM_CANCELLATION : RecoveryAction.MANUAL_REVIEW;
        return new RecoveryCandidate("CANCELLATION", stale.paymentId() + ":" + stale.cancellationId(), action,
                "Cancellation remains in " + stale.state() + ".", stale.updatedAt());
    }

    private Duration requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be positive.");
        }
        return duration;
    }
}
