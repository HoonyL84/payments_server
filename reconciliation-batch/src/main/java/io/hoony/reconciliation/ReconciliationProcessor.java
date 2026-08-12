package io.hoony.reconciliation;

import org.springframework.batch.item.ItemProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

final class ReconciliationProcessor
        implements ItemProcessor<ReconciliationModel.PaymentRow, ReconciliationModel.Result> {

    private final String runKey;
    private final long failAfter;
    private final ReconciliationFailureGate failureGate;
    private final AtomicLong processed = new AtomicLong();

    ReconciliationProcessor(String runKey, long failAfter, ReconciliationFailureGate failureGate) {
        this.runKey = runKey;
        this.failAfter = failAfter;
        this.failureGate = failureGate;
    }

    @Override
    public ReconciliationModel.Result process(ReconciliationModel.PaymentRow row) {
        long current = processed.incrementAndGet();
        if (failAfter > 0 && current >= failAfter && failureGate.armOnce(runKey)) {
            throw new IllegalStateException("Injected reconciliation chunk failure.");
        }

        List<ReconciliationModel.Finding> findings = new ArrayList<>();
        paymentVsPg(row, findings);
        cancellationVsPg(row, findings);
        ledger(row, findings);
        delivery(row, findings);
        return new ReconciliationModel.Result(row.paymentId(), findings);
    }

    private static void paymentVsPg(
            ReconciliationModel.PaymentRow row,
            List<ReconciliationModel.Finding> findings
    ) {
        if ("PENDING_CONFIRMATION".equals(row.paymentState()) || "CONFIRMING".equals(row.paymentState())) {
            findings.add(finding(
                    ReconciliationModel.CaseType.PAYMENT_PG_MISMATCH,
                    ReconciliationModel.Classification.REQUERY_REQUIRED,
                    "final payment state",
                    row.paymentState() + "/" + row.pgApprovalStatus(),
                    "Payment requires a fresh PG status inquiry.",
                    null
            ));
            return;
        }

        boolean internalApproved = "APPROVED".equals(row.paymentState());
        boolean pgApproved = "APPROVED".equals(row.pgApprovalStatus());
        if (internalApproved != pgApproved) {
            ReconciliationModel.Classification classification =
                    "MISSING".equals(row.pgApprovalStatus())
                            ? ReconciliationModel.Classification.REQUERY_REQUIRED
                            : ReconciliationModel.Classification.MANUAL_REVIEW;
            findings.add(finding(
                    ReconciliationModel.CaseType.PAYMENT_PG_MISMATCH,
                    classification,
                    row.paymentState(),
                    row.pgApprovalStatus(),
                    "Internal payment and PG approval results do not match.",
                    null
            ));
        }
    }

    private static void cancellationVsPg(
            ReconciliationModel.PaymentRow row,
            List<ReconciliationModel.Finding> findings
    ) {
        if (row.canceledAmountMinorUnits() != row.successfulCancellationAmount()) {
            findings.add(finding(
                    ReconciliationModel.CaseType.CANCELLATION_PG_MISMATCH,
                    ReconciliationModel.Classification.MANUAL_REVIEW,
                    Long.toString(row.canceledAmountMinorUnits()),
                    Long.toString(row.successfulCancellationAmount()),
                    "Payment canceled amount and successful cancellation rows do not match.",
                    null
            ));
        } else if (row.canceledAmountMinorUnits() > 0
                && row.canceledAmountMinorUnits() != row.pgCanceledAmount()) {
            findings.add(finding(
                    ReconciliationModel.CaseType.CANCELLATION_PG_MISMATCH,
                    ReconciliationModel.Classification.REQUERY_REQUIRED,
                    Long.toString(row.canceledAmountMinorUnits()),
                    Long.toString(row.pgCanceledAmount()),
                    "PG cancellation result must be queried again.",
                    null
            ));
        }
    }

    private static void ledger(
            ReconciliationModel.PaymentRow row,
            List<ReconciliationModel.Finding> findings
    ) {
        if ("APPROVED".equals(row.paymentState())
                && (row.approvalDebit() != row.amountMinorUnits()
                || row.approvalCredit() != row.amountMinorUnits())) {
            findings.add(finding(
                    ReconciliationModel.CaseType.PAYMENT_LEDGER_MISMATCH,
                    ReconciliationModel.Classification.MANUAL_REVIEW,
                    row.amountMinorUnits() + "/" + row.amountMinorUnits(),
                    row.approvalDebit() + "/" + row.approvalCredit(),
                    "Approval amount and ledger debit/credit totals do not match.",
                    null
            ));
        }

        if (row.canceledAmountMinorUnits() != row.cancellationDebit()
                || row.canceledAmountMinorUnits() != row.cancellationCredit()) {
            findings.add(finding(
                    ReconciliationModel.CaseType.CANCELLATION_LEDGER_MISMATCH,
                    ReconciliationModel.Classification.MANUAL_REVIEW,
                    row.canceledAmountMinorUnits() + "/" + row.canceledAmountMinorUnits(),
                    row.cancellationDebit() + "/" + row.cancellationCredit(),
                    "Cancellation amount and ledger debit/credit totals do not match.",
                    null
            ));
        }
    }

    private static void delivery(
            ReconciliationModel.PaymentRow row,
            List<ReconciliationModel.Finding> findings
    ) {
        if (row.outboxCount() == 0 || row.pendingOutboxCount() > 0) {
            findings.add(finding(
                    ReconciliationModel.CaseType.OUTBOX_MISSING_OR_PENDING,
                    ReconciliationModel.Classification.AUTO_CORRECT,
                    "published outbox event",
                    "total=" + row.outboxCount() + ",pending=" + row.pendingOutboxCount(),
                    "Outbox relay command can be retried safely.",
                    "RELAY_OUTBOX"
            ));
        } else if (row.publishedOutboxCount() > 0 && row.consumerEffectCount() == 0) {
            findings.add(finding(
                    ReconciliationModel.CaseType.KAFKA_CONSUMPTION_MISSING,
                    ReconciliationModel.Classification.AUTO_CORRECT,
                    "consumer side effect",
                    "0",
                    "Published event has no consumer side effect.",
                    "REPLAY_EVENT"
            ));
        }
    }

    private static ReconciliationModel.Finding finding(
            ReconciliationModel.CaseType type,
            ReconciliationModel.Classification classification,
            String expected,
            String actual,
            String detail,
            String command
    ) {
        return new ReconciliationModel.Finding(type, classification, expected, actual, detail, command);
    }
}