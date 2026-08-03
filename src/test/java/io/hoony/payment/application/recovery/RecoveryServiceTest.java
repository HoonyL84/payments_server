package io.hoony.payment.application.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hoony.payment.application.cancellation.ConfirmCancellationService;
import io.hoony.payment.application.confirmation.ConfirmPaymentService;
import io.hoony.payment.application.ledger.LedgerConsistencyService;
import io.hoony.payment.application.outbox.OutboxRelayService;
import io.hoony.payment.application.port.out.CancellationRepository;
import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.cancellation.CancellationState;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxEventType;
import io.hoony.payment.domain.payment.PaymentState;
import io.hoony.payment.observability.PaymentMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final CancellationRepository cancellations = mock(CancellationRepository.class);
    private final OutboxEventRepository outboxEvents = mock(OutboxEventRepository.class);
    private final LedgerConsistencyService ledger = mock(LedgerConsistencyService.class);
    private final ConfirmPaymentService confirmPayment = mock(ConfirmPaymentService.class);
    private final ConfirmCancellationService confirmCancellation = mock(ConfirmCancellationService.class);
    private final OutboxRelayService outboxRelay = mock(OutboxRelayService.class);
    private final PaymentMetrics metrics = mock(PaymentMetrics.class);
    private final RecoveryService service = new RecoveryService(
            payments, cancellations, outboxEvents, ledger, confirmPayment,
            confirmCancellation, outboxRelay, metrics, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void classifiesSafeConfirmationAndAmbiguousProcessingSeparately() {
        UUID pendingId = UUID.randomUUID();
        UUID processingId = UUID.randomUUID();
        when(payments.findStaleProcessing(NOW.minusSeconds(60), 500)).thenReturn(List.of(
                new StalePayment(pendingId, PaymentState.PENDING_CONFIRMATION, NOW.minusSeconds(120)),
                new StalePayment(processingId, PaymentState.APPROVING, NOW.minusSeconds(120))));
        when(cancellations.findStaleProcessing(NOW.minusSeconds(60), 500)).thenReturn(List.of());
        when(outboxEvents.findPendingBefore(NOW.minusSeconds(60), 500)).thenReturn(List.of());
        when(ledger.countDrifts()).thenReturn(2L);

        RecoveryReport report = service.inspect(Duration.ofSeconds(60));

        assertThat(report.autoRecoverableCount()).isEqualTo(1);
        assertThat(report.manualReviewCount()).isEqualTo(2);
        assertThat(report.ledgerDriftCount()).isEqualTo(2);
        assertThat(report.candidates()).extracting(RecoveryCandidate::action)
                .containsExactly(
                        RecoveryAction.CONFIRM_PAYMENT,
                        RecoveryAction.MANUAL_REVIEW,
                        RecoveryAction.MANUAL_REVIEW);
    }

    @Test
    void confirmsPendingResourcesAndRelaysOldOutbox() {
        UUID paymentId = UUID.randomUUID();
        UUID cancellationId = UUID.randomUUID();
        OutboxEvent outbox = OutboxEvent.pending(
                UUID.randomUUID(), paymentId, OutboxEventType.PAYMENT_APPROVED, "{}", NOW.minusSeconds(120));
        when(payments.findStaleProcessing(NOW.minusSeconds(60), 500)).thenReturn(List.of(
                new StalePayment(paymentId, PaymentState.PENDING_CONFIRMATION, NOW.minusSeconds(120))));
        when(cancellations.findStaleProcessing(NOW.minusSeconds(60), 500)).thenReturn(List.of(
                new StaleCancellation(paymentId, cancellationId,
                        CancellationState.CANCEL_PENDING_CONFIRMATION, NOW.minusSeconds(120))));
        when(outboxEvents.findPendingBefore(NOW.minusSeconds(60), 500)).thenReturn(List.of(outbox));
        when(ledger.countDrifts()).thenReturn(0L);
        when(outboxRelay.relayPending(100)).thenReturn(1);

        RecoveryRunResult result = service.recover(Duration.ofSeconds(60));

        verify(confirmPayment).confirm(paymentId);
        verify(confirmCancellation).confirm(paymentId, cancellationId);
        verify(outboxRelay).relayPending(100);
        assertThat(result.recoveredCount()).isEqualTo(3);
        assertThat(result.failures()).isEmpty();
    }
}
