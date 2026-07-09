package io.hoony.payment.application.approval;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.payment.PaymentState;

import java.util.UUID;

/**
 * Result returned by the payment approval use case.
 */
public record ApprovePaymentResult(
        UUID paymentId,
        PaymentState state,
        boolean reused
) {

    /**
     * Validates the approval result.
     */
    public ApprovePaymentResult {
        if (paymentId == null) {
            throw new DomainException("paymentId is required.");
        }
        if (state == null) {
            throw new DomainException("state is required.");
        }
    }

    /**
     * Serializes the reusable response stored in the idempotency record.
     */
    public String toStoredResponse() {
        return paymentId + "|" + state;
    }

    /**
     * Recreates an approval result from the idempotency record response.
     */
    public static ApprovePaymentResult fromStoredResponse(String responseBody) {
        String[] parts = responseBody.split("\\|");
        if (parts.length != 2) {
            throw new DomainException("Stored approval response is invalid.");
        }
        return new ApprovePaymentResult(UUID.fromString(parts[0]), PaymentState.valueOf(parts[1]), true);
    }
}