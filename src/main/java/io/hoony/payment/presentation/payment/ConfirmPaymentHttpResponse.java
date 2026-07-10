package io.hoony.payment.presentation.payment;

import io.hoony.payment.application.confirmation.ConfirmPaymentResult;
import io.hoony.payment.domain.payment.PaymentState;

import java.util.UUID;

public record ConfirmPaymentHttpResponse(UUID paymentId, PaymentState state) {

    public static ConfirmPaymentHttpResponse from(ConfirmPaymentResult result) {
        return new ConfirmPaymentHttpResponse(result.paymentId(), result.state());
    }
}
