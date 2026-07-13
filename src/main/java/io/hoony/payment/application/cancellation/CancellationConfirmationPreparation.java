package io.hoony.payment.application.cancellation;

import java.util.UUID;

public record CancellationConfirmationPreparation(
        UUID paymentId,
        UUID cancellationId,
        UUID attemptId,
        String provider,
        String routingKey,
        String originalProviderRequestId
) {
}