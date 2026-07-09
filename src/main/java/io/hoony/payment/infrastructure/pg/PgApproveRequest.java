package io.hoony.payment.infrastructure.pg;

import io.hoony.payment.domain.money.Money;

import java.util.UUID;

public record PgApproveRequest(
        UUID paymentId,
        String merchantId,
        String orderId,
        Money amount
) {
}