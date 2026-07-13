package io.hoony.payment.infrastructure.persistence.repository;

import io.hoony.payment.infrastructure.persistence.entity.MerchantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaMerchantEntityRepository extends JpaRepository<MerchantEntity, String> {
}
