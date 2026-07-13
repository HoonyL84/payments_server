package io.hoony.payment.application.confirmation;

import java.util.UUID;

public record ConfirmationPreparation(
        UUID paymentId,
        UUID attemptId,
        String provider,
        String routingKey,
        String providerRequestId
) {
}
