package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.payment.Payment;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    void save(Payment payment);

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByIdForUpdate(UUID id);

    Optional<Payment> findByMerchantIdAndOrderId(String merchantId, String orderId);

    boolean claimForConfirmation(UUID id);

    long count();
}