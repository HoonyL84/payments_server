package io.hoony.payment.application.recovery;

import java.time.Instant;

public record RecoveryCandidate(
        String type,
        String resourceId,
        RecoveryAction action,
        String reason,
        Instant detectedFrom
) {
}
