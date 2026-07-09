package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.payment.Payment;

import java.util.Optional;
import java.util.UUID;

/**
 * Stores payment aggregates.
 */
public interface PaymentRepository {

    /**
     * Saves a payment aggregate.
     */
    void save(Payment payment);

    /**
     * Finds a payment by id.
     */
    Optional<Payment> findById(UUID id);

    /**
     * Finds a payment by merchant order identity.
     */
    Optional<Payment> findByMerchantIdAndOrderId(String merchantId, String orderId);

    /**
     * Counts all payments.
     */
    long count();
}