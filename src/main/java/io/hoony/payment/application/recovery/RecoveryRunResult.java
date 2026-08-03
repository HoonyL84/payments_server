package io.hoony.payment.application.recovery;

import java.util.List;

public record RecoveryRunResult(int recoveredCount, int manualReviewCount, List<String> failures) {
}
