package io.hoony.payment.application.recovery;

import io.hoony.payment.domain.payment.PaymentState;
import java.time.Instant;
import java.util.UUID;

public record StalePayment(UUID paymentId, PaymentState state, Instant updatedAt) {
}
