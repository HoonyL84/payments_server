package io.hoony.payment.application.approval;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.payment.PaymentState;

import java.util.UUID;

public record ApprovePaymentResult(
        UUID paymentId,
        PaymentState state,
        boolean reused
) {

    public ApprovePaymentResult {
        if (paymentId == null) {
            throw new DomainException("paymentId is required.");
        }
        if (state == null) {
            throw new DomainException("state is required.");
        }
    }

    public String toStoredResponse() {
        return paymentId + "|" + state;
    }

    public static ApprovePaymentResult fromStoredResponse(String responseBody) {
        try {
            String[] parts = responseBody.split("\\|");
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            return new ApprovePaymentResult(
                    UUID.fromString(parts[0]),
                    PaymentState.valueOf(parts[1]),
                    true
            );
        } catch (IllegalArgumentException exception) {
            throw new DomainException("Stored approval response is invalid.");
        }
    }
}
