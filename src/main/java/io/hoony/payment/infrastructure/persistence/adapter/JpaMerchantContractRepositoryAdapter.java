package io.hoony.payment.infrastructure.persistence.adapter;

import io.hoony.payment.application.port.out.MerchantContractRepository;
import io.hoony.payment.domain.merchant.MerchantContract;
import io.hoony.payment.infrastructure.persistence.entity.MerchantEntity;
import io.hoony.payment.infrastructure.persistence.repository.JpaMerchantEntityRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Profile("!test")
@Repository
public class JpaMerchantContractRepositoryAdapter implements MerchantContractRepository {

    private final JpaMerchantEntityRepository repository;

    public JpaMerchantContractRepositoryAdapter(JpaMerchantEntityRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<MerchantContract> findById(String merchantId) {
        return repository.findById(merchantId).map(MerchantEntity::toDomain);
    }
}
