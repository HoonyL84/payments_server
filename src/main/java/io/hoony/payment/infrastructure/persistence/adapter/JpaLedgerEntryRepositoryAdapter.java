package io.hoony.payment.infrastructure.persistence.adapter;

import io.hoony.payment.application.port.out.LedgerEntryRepository;
import io.hoony.payment.domain.ledger.LedgerEntry;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.infrastructure.persistence.entity.LedgerEntryEntity;
import io.hoony.payment.infrastructure.persistence.repository.JpaLedgerEntryEntityRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Profile("!test")
@Repository
public class JpaLedgerEntryRepositoryAdapter implements LedgerEntryRepository {

    private final JpaLedgerEntryEntityRepository repository;

    public JpaLedgerEntryRepositoryAdapter(JpaLedgerEntryEntityRepository repository) {
        this.repository = repository;
    }

    @Override
    public void saveAll(List<LedgerEntry> entries) {
        boolean duplicateId = entries.stream()
                .map(entry -> entry.id().toString())
                .anyMatch(repository::existsById);
        if (duplicateId) {
            throw new ResourceConflictException("Ledger entries are append-only.");
        }
        repository.saveAllAndFlush(entries.stream().map(LedgerEntryEntity::new).toList());
    }

    @Override
    public List<LedgerEntry> findByPaymentId(UUID paymentId) {
        return repository.findByPaymentId(paymentId.toString()).stream()
                .map(LedgerEntryEntity::toDomain)
                .toList();
    }

    @Override
    public List<LedgerEntry> findAll() {
        return repository.findAll().stream().map(LedgerEntryEntity::toDomain).toList();
    }
}
