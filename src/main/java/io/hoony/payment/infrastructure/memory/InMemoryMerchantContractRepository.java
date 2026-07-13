package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.MerchantContractRepository;
import io.hoony.payment.domain.merchant.MerchantContract;
import io.hoony.payment.domain.merchant.MerchantStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

@Profile("test")
@Repository
public class InMemoryMerchantContractRepository implements MerchantContractRepository {

    private final Map<String, MerchantContract> merchants = Map.of(
            "merchant-1",
            new MerchantContract("merchant-1", MerchantStatus.ACTIVE, "FAKE_PG", "route-merchant-1"),
            "merchant-2",
            new MerchantContract("merchant-2", MerchantStatus.ACTIVE, "FAKE_PG", "route-merchant-2")
    );

    @Override
    public Optional<MerchantContract> findById(String merchantId) {
        return Optional.ofNullable(merchants.get(merchantId));
    }
}
