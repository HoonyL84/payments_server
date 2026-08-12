package io.hoony.reconciliation;

import java.util.List;

final class ReconciliationModel {

    private ReconciliationModel() {
    }

    enum Classification {
        AUTO_CORRECT,
        REQUERY_REQUIRED,
        MANUAL_REVIEW
    }

    enum CaseType {
        PAYMENT_PG_MISMATCH,
        CANCELLATION_PG_MISMATCH,
        PAYMENT_LEDGER_MISMATCH,
        CANCELLATION_LEDGER_MISMATCH,
        OUTBOX_MISSING_OR_PENDING,
        KAFKA_CONSUMPTION_MISSING
    }

    record PaymentRow(
            String paymentId,
            String paymentState,
            long amountMinorUnits,
            long canceledAmountMinorUnits,
            String pgApprovalStatus,
            long successfulCancellationAmount,
            long pgCanceledAmount,
            long approvalDebit,
            long approvalCredit,
            long cancellationDebit,
            long cancellationCredit,
            long outboxCount,
            long pendingOutboxCount,
            long publishedOutboxCount,
            long consumerEffectCount
    ) {
    }

    record Finding(
            CaseType type,
            Classification classification,
            String expected,
            String actual,
            String detail,
            String correctionCommand
    ) {
    }

    record Result(String paymentId, List<Finding> findings) {
        Result {
            findings = List.copyOf(findings);
        }
    }
}