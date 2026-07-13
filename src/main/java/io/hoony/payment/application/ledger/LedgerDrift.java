package io.hoony.payment.application.ledger;

import java.util.UUID;

public record LedgerDrift(
        UUID transactionGroupId,
        String currency,
        long debitMinorUnits,
        long creditMinorUnits
) {
}
