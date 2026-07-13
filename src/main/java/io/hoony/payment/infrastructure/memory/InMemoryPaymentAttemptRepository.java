package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.domain.attempt.PaymentAttempt;
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
    public List<PaymentAttempt> findAll() {
        return new ArrayList<>(attempts.values());
    }
}
