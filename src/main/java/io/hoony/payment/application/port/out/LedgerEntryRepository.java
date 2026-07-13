package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.ledger.LedgerEntry;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository {

    void saveAll(List<LedgerEntry> entries);

    List<LedgerEntry> findByPaymentId(UUID paymentId);

    List<LedgerEntry> findAll();
}
