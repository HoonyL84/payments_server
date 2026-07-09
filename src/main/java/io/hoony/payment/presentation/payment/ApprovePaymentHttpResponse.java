package io.hoony.payment.presentation.payment;

import io.hoony.payment.application.approval.ApprovePaymentResult;
import io.hoony.payment.domain.payment.PaymentState;

import java.util.UUID;

public record ApprovePaymentHttpResponse(
        UUID paymentId,
        PaymentState state,
        boolean reused
) {
    public static ApprovePaymentHttpResponse from(ApprovePaymentResult result) {
        return new ApprovePaymentHttpResponse(result.paymentId(), result.state(), result.reused());
    }
}