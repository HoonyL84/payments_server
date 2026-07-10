package io.hoony.payment.infrastructure.pg;

import java.util.UUID;

public record PgConfirmApproveRequest(UUID paymentId) {
}
