package io.hoony.payment.application.recovery;

import io.hoony.payment.domain.cancellation.CancellationState;
import java.time.Instant;
import java.util.UUID;

public record StaleCancellation(UUID paymentId, UUID cancellationId, CancellationState state, Instant updatedAt) {
}
