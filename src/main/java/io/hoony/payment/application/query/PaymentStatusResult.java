package io.hoony.payment.application.query;

import io.hoony.payment.domain.payment.PaymentState;

import java.util.UUID;

public record PaymentStatusResult(UUID paymentId, PaymentState state) {
}
