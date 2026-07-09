package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.domain.attempt.PaymentAttempt;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class InMemoryPaymentAttemptRepository implements PaymentAttemptRepository {

    private final List<PaymentAttempt> attempts = new CopyOnWriteArrayList<>();

    @Override
    public void save(PaymentAttempt attempt) {
        attempts.add(attempt);
    }

    @Override
    public List<PaymentAttempt> findAll() {
        return new ArrayList<>(attempts);
    }
}