package io.hoony.payment.infrastructure.persistence.repository;

import io.hoony.payment.infrastructure.persistence.entity.LedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JpaLedgerEntryEntityRepository
        extends JpaRepository<LedgerEntryEntity, String> {

    List<LedgerEntryEntity> findByPaymentId(String paymentId);
}
