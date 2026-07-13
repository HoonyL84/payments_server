package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.domain.attempt.PaymentOperation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository {

    void save(PaymentAttempt attempt);

    Optional<PaymentAttempt> findById(UUID id);

    Optional<PaymentAttempt> findLatestSuccessful(UUID paymentId, PaymentOperation operation);

    List<PaymentAttempt> findAll();
}