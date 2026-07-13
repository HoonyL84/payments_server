package io.hoony.payment.application.cancellation;

import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.domain.cancellation.PaymentCancellation;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import io.hoony.payment.domain.payment.Payment;

public record CancellationPreparation(
        boolean replay,
        CancelPaymentResult replayedResult,
        IdempotencyScope scope,
        Payment payment,
        PaymentCancellation cancellation,
        PaymentAttempt attempt,
        String provider,
        String routingKey,
        String originalProviderTransactionId
) {
    public static CancellationPreparation replay(CancelPaymentResult result) {
        return new CancellationPreparation(true, result, null, null, null, null, null, null, null);
    }

    public static CancellationPreparation newCancellation(
            IdempotencyScope scope,
            Payment payment,
            PaymentCancellation cancellation,
            PaymentAttempt attempt,
            String provider,
            String routingKey,
            String originalProviderTransactionId
    ) {
        return new CancellationPreparation(
                false,
                null,
                scope,
                payment,
                cancellation,
                attempt,
                provider,
                routingKey,
                originalProviderTransactionId
        );
    }

    public boolean isReplay() {
        return replay;
    }
}