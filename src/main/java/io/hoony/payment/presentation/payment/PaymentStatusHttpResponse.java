package io.hoony.payment.presentation.payment;

import io.hoony.payment.application.query.PaymentStatusResult;
import io.hoony.payment.domain.payment.PaymentState;

import java.util.UUID;

public record PaymentStatusHttpResponse(UUID paymentId, PaymentState state) {

    public static PaymentStatusHttpResponse from(PaymentStatusResult result) {
        return new PaymentStatusHttpResponse(result.paymentId(), result.state());
    }
}
