package io.hoony.payment.application.recovery;

import java.time.Instant;
import java.util.List;

public record RecoveryReport(
        Instant generatedAt,
        int autoRecoverableCount,
        int manualReviewCount,
        long ledgerDriftCount,
        List<RecoveryCandidate> candidates
) {
}
