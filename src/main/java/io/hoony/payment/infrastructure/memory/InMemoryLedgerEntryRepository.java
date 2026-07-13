package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.LedgerEntryRepository;
import io.hoony.payment.domain.ledger.LedgerEntry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Profile("test")
@Repository
public class InMemoryLedgerEntryRepository implements LedgerEntryRepository {

    private final List<LedgerEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void saveAll(List<LedgerEntry> entries) {
        this.entries.addAll(entries);
    }

    @Override
    public List<LedgerEntry> findByPaymentId(UUID paymentId) {
        return entries.stream().filter(entry -> entry.paymentId().equals(paymentId)).toList();
    }

    @Override
    public List<LedgerEntry> findAll() {
        return List.copyOf(entries);
    }
}
