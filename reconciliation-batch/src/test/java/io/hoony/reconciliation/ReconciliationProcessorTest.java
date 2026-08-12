package io.hoony.reconciliation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconciliationProcessorTest {

    private final ReconciliationFailureGate failureGate = mock(ReconciliationFailureGate.class);

    @Test
    void cleanPaymentHasNoFinding() {
        ReconciliationProcessor processor = new ReconciliationProcessor("run", 0, failureGate);

        assertThat(processor.process(clean()).findings()).isEmpty();
    }

    @Test
    void classifiesPgLedgerAndDeliveryDifferences() {
        ReconciliationProcessor processor = new ReconciliationProcessor("run", 0, failureGate);

        assertFinding(
                processor.process(row("APPROVED", "DECLINED", 1000, 1000, 1, 0, 1)),
                ReconciliationModel.CaseType.PAYMENT_PG_MISMATCH,
                ReconciliationModel.Classification.MANUAL_REVIEW
        );
        assertFinding(
                processor.process(row("PENDING_CONFIRMATION", "APPROVED", 1000, 1000, 1, 0, 1)),
                ReconciliationModel.CaseType.PAYMENT_PG_MISMATCH,
                ReconciliationModel.Classification.REQUERY_REQUIRED
        );
        assertFinding(
                processor.process(row("APPROVED", "APPROVED", 0, 0, 1, 0, 1)),
                ReconciliationModel.CaseType.PAYMENT_LEDGER_MISMATCH,
                ReconciliationModel.Classification.MANUAL_REVIEW
        );
        assertFinding(
                processor.process(row("APPROVED", "APPROVED", 1000, 1000, 1, 1, 0)),
                ReconciliationModel.CaseType.OUTBOX_MISSING_OR_PENDING,
                ReconciliationModel.Classification.AUTO_CORRECT
        );
        assertFinding(
                processor.process(row("APPROVED", "APPROVED", 1000, 1000, 1, 0, 0)),
                ReconciliationModel.CaseType.KAFKA_CONSUMPTION_MISSING,
                ReconciliationModel.Classification.AUTO_CORRECT
        );
    }

    @Test
    void injectedFailureIsConsumedOnlyOnce() throws Exception {
        when(failureGate.armOnce("restart-run")).thenReturn(true, false);
        ReconciliationProcessor first = new ReconciliationProcessor("restart-run", 2, failureGate);

        first.process(clean());
        assertThatThrownBy(() -> first.process(clean()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Injected");

        ReconciliationProcessor restarted = new ReconciliationProcessor("restart-run", 2, failureGate);
        restarted.process(clean());
        assertThat(restarted.process(clean()).findings()).isEmpty();
    }

    private static void assertFinding(
            ReconciliationModel.Result result,
            ReconciliationModel.CaseType type,
            ReconciliationModel.Classification classification
    ) {
        assertThat(result.findings())
                .anySatisfy(finding -> {
                    assertThat(finding.type()).isEqualTo(type);
                    assertThat(finding.classification()).isEqualTo(classification);
                });
    }

    private static ReconciliationModel.PaymentRow clean() {
        return row("APPROVED", "APPROVED", 1000, 1000, 1, 0, 1);
    }

    private static ReconciliationModel.PaymentRow row(
            String paymentState,
            String pgStatus,
            long approvalDebit,
            long approvalCredit,
            long outboxCount,
            long pendingOutboxCount,
            long consumerEffectCount
    ) {
        return new ReconciliationModel.PaymentRow(
                "payment-1",
                paymentState,
                1000,
                0,
                pgStatus,
                0,
                0,
                approvalDebit,
                approvalCredit,
                0,
                0,
                outboxCount,
                pendingOutboxCount,
                outboxCount - pendingOutboxCount,
                consumerEffectCount
        );
    }
}