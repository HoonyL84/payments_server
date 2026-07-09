package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.attempt.PaymentAttempt;

import java.util.List;

/**
 * Stores PG attempt history.
 */
public interface PaymentAttemptRepository {

    /**
     * Saves one PG attempt.
     */
    void save(PaymentAttempt attempt);

    /**
     * Returns all attempts for tests and consistency checks.
     */
    List<PaymentAttempt> findAll();
}