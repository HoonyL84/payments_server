package io.hoony.payment.presentation.payment;

import io.hoony.payment.application.cancellation.CancelPaymentResult;
import io.hoony.payment.domain.cancellation.CancellationState;

import java.util.UUID;

public record CancelPaymentHttpResponse(
        UUID paymentId,
        UUID cancellationId,
        CancellationState state
) {
    public static CancelPaymentHttpResponse from(CancelPaymentResult result) {
        return new CancelPaymentHttpResponse(
                result.paymentId(),
                result.cancellationId(),
                result.state()
        );
    }
}