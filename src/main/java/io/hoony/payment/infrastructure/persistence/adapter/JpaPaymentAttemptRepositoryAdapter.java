package io.hoony.payment.infrastructure.persistence.adapter;

import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.infrastructure.persistence.entity.PaymentAttemptEntity;
import io.hoony.payment.infrastructure.persistence.repository.JpaPaymentAttemptEntityRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Profile("!test")
@Repository
public class JpaPaymentAttemptRepositoryAdapter implements PaymentAttemptRepository {

    private final JpaPaymentAttemptEntityRepository repository;

    public JpaPaymentAttemptRepositoryAdapter(JpaPaymentAttemptEntityRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(PaymentAttempt attempt) {
        PaymentAttemptEntity entity = repository.findById(attempt.id().toString())
                .orElseGet(() -> new PaymentAttemptEntity(attempt));
        entity.update(attempt);
        repository.saveAndFlush(entity);
    }

    @Override
    public Optional<PaymentAttempt> findById(UUID id) {
        return repository.findById(id.toString()).map(PaymentAttemptEntity::toDomain);
    }

    @Override
    public List<PaymentAttempt> findAll() {
        return repository.findAll().stream().map(PaymentAttemptEntity::toDomain).toList();
    }
}
