package io.hoony.payment.application.cancellation;

import io.hoony.payment.domain.cancellation.CancellationState;
import io.hoony.payment.domain.common.DomainException;

import java.util.UUID;

public record CancelPaymentResult(
        UUID paymentId,
        UUID cancellationId,
        CancellationState state,
        boolean reused
) {
    public CancelPaymentResult {
        if (paymentId == null || cancellationId == null || state == null) {
            throw new DomainException("Cancellation result is incomplete.");
        }
    }

    public String toStoredResponse() {
        return paymentId + "|" + cancellationId + "|" + state;
    }

    public static CancelPaymentResult fromStoredResponse(String responseBody) {
        try {
            String[] parts = responseBody.split("\\|");
            if (parts.length != 3) {
                throw new IllegalArgumentException();
            }
            return new CancelPaymentResult(
                    UUID.fromString(parts[0]),
                    UUID.fromString(parts[1]),
                    CancellationState.valueOf(parts[2]),
                    true
            );
        } catch (IllegalArgumentException exception) {
            throw new DomainException("Stored cancellation response is invalid.");
        }
    }
}