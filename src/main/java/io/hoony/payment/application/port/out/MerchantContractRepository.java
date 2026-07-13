package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.merchant.MerchantContract;

import java.util.Optional;

public interface MerchantContractRepository {

    Optional<MerchantContract> findById(String merchantId);
}
