package io.hoony.payment.application.ledger;

import io.hoony.payment.application.port.out.LedgerEntryRepository;
import io.hoony.payment.domain.ledger.LedgerDirection;
import io.hoony.payment.domain.ledger.LedgerEntry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class LedgerConsistencyService {

    private final LedgerEntryRepository ledgerEntries;

    public LedgerConsistencyService(LedgerEntryRepository ledgerEntries) {
        this.ledgerEntries = ledgerEntries;
    }

    @Transactional(readOnly = true)
    public List<LedgerDrift> findDrifts() {
        Map<GroupKey, List<LedgerEntry>> groups = ledgerEntries.findAll().stream()
                .collect(Collectors.groupingBy(entry -> new GroupKey(
                        entry.transactionGroupId(),
                        entry.amount().currency()
                )));

        return groups.entrySet().stream()
                .map(entry -> toDrift(entry.getKey(), entry.getValue()))
                .filter(drift -> drift.debitMinorUnits() != drift.creditMinorUnits())
                .toList();
    }

    private LedgerDrift toDrift(GroupKey key, List<LedgerEntry> entries) {
        long debit = entries.stream()
                .filter(entry -> entry.direction() == LedgerDirection.DEBIT)
                .mapToLong(entry -> entry.amount().minorUnits())
                .sum();
        long credit = entries.stream()
                .filter(entry -> entry.direction() == LedgerDirection.CREDIT)
                .mapToLong(entry -> entry.amount().minorUnits())
                .sum();
        return new LedgerDrift(key.transactionGroupId(), key.currency(), debit, credit);
    }

    private record GroupKey(java.util.UUID transactionGroupId, String currency) {
    }
}
