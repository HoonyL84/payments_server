package io.hoony.payment.presentation.payment;

import io.hoony.payment.application.cancellation.ConfirmCancellationResult;
import io.hoony.payment.domain.cancellation.CancellationState;

import java.util.UUID;

public record ConfirmCancellationHttpResponse(
        UUID paymentId,
        UUID cancellationId,
        CancellationState state
) {
    public static ConfirmCancellationHttpResponse from(ConfirmCancellationResult result) {
        return new ConfirmCancellationHttpResponse(
                result.paymentId(),
                result.cancellationId(),
                result.state()
        );
    }
}