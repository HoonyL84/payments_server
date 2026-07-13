package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.attempt.PaymentAttempt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository {

    void save(PaymentAttempt attempt);

    Optional<PaymentAttempt> findById(UUID id);

    List<PaymentAttempt> findAll();
}
