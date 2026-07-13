package io.hoony.payment.application.approval;

import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.domain.payment.Payment;

public record ApprovalPreparation(
        Payment payment,
        PaymentAttempt attempt,
        String provider,
        String routingKey,
        ApprovePaymentResult replayedResult
) {

    public static ApprovalPreparation newApproval(
            Payment payment,
            PaymentAttempt attempt,
            String provider,
            String routingKey
    ) {
        return new ApprovalPreparation(payment, attempt, provider, routingKey, null);
    }

    public static ApprovalPreparation replay(ApprovePaymentResult result) {
        return new ApprovalPreparation(null, null, null, null, result);
    }

    public boolean isReplay() {
        return replayedResult != null;
    }
}
