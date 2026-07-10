package io.hoony.payment.application.confirmation;

import io.hoony.payment.domain.payment.PaymentState;

import java.util.UUID;

public record ConfirmPaymentResult(UUID paymentId, PaymentState state) {
}
