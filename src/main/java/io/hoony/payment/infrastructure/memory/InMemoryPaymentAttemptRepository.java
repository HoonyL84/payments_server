package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.domain.attempt.PaymentAttemptResult;
import io.hoony.payment.domain.attempt.PaymentOperation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Profile("test")
@Repository
public class InMemoryPaymentAttemptRepository implements PaymentAttemptRepository {

    private final ConcurrentMap<UUID, PaymentAttempt> attempts = new ConcurrentHashMap<>();

    @Override
    public void save(PaymentAttempt attempt) {
        attempts.put(attempt.id(), attempt);
    }

    @Override
    public Optional<PaymentAttempt> findById(UUID id) {
        return Optional.ofNullable(attempts.get(id));
    }

    @Override
    public Optional<PaymentAttempt> findLatestSuccessful(UUID paymentId, PaymentOperation operation) {
        return attempts.values().stream()
                .filter(attempt -> attempt.paymentId().equals(paymentId))
                .filter(attempt -> attempt.operation() == operation)
                .filter(attempt -> attempt.result() == PaymentAttemptResult.SUCCEEDED)
                .max(java.util.Comparator.comparing(PaymentAttempt::completedAt));
    }
    @Override
    public List<PaymentAttempt> findAll() {
        return new ArrayList<>(attempts.values());
    }
}
