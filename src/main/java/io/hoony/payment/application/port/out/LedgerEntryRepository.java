package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.ledger.LedgerEntry;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository {

    void saveAll(List<LedgerEntry> entries);

    List<LedgerEntry> findByPaymentId(UUID paymentId);

    List<LedgerEntry> findAll();

    default long countDrifts() {
        return findAll().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        entry -> entry.transactionGroupId() + "|" + entry.amount().currency(),
                        java.util.stream.Collectors.summingLong(entry ->
                                entry.direction() == io.hoony.payment.domain.ledger.LedgerDirection.DEBIT
                                        ? entry.amount().minorUnits()
                                        : -entry.amount().minorUnits())))
                .values().stream()
                .filter(balance -> balance != 0)
                .count();
    }
}
