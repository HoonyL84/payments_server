package io.hoony.payment.application.cancellation;

import io.hoony.payment.domain.cancellation.CancellationState;

import java.util.UUID;

public record ConfirmCancellationResult(
        UUID paymentId,
        UUID cancellationId,
        CancellationState state
) {
}