package io.hoony.payment.infrastructure.persistence.repository;

import io.hoony.payment.infrastructure.persistence.entity.LedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface JpaLedgerEntryEntityRepository
        extends JpaRepository<LedgerEntryEntity, String> {

    List<LedgerEntryEntity> findByPaymentId(String paymentId);

    @Query(value = """
            SELECT COUNT(*)
              FROM (
                    SELECT transaction_group_id, currency
                      FROM ledger_entries
                     GROUP BY transaction_group_id, currency
                    HAVING SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor_units ELSE 0 END)
                         <> SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor_units ELSE 0 END)
                   ) drift
            """, nativeQuery = true)
    long countDrifts();
}
