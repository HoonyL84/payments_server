package io.hoony.payment.infrastructure.persistence.adapter;

import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.payment.Payment;
import io.hoony.payment.domain.payment.PaymentState;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.infrastructure.persistence.entity.PaymentEntity;
import io.hoony.payment.infrastructure.persistence.repository.JpaPaymentEntityRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Profile("!test")
@Repository
public class JpaPaymentRepositoryAdapter implements PaymentRepository {

    private final JpaPaymentEntityRepository repository;
    private final Clock clock;

    public JpaPaymentRepositoryAdapter(JpaPaymentEntityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void save(Payment payment) {
        Instant now = Instant.now(clock);
        PaymentEntity entity = repository.findById(payment.id().toString())
                .orElseGet(() -> new PaymentEntity(payment, now));
        entity.update(payment, now);
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("Payment already exists for merchant order.");
        }
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return repository.findById(id.toString()).map(PaymentEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id.toString()).map(PaymentEntity::toDomain);
    }
    @Override
    public Optional<Payment> findByMerchantIdAndOrderId(String merchantId, String orderId) {
        return repository.findByMerchantIdAndOrderId(merchantId, orderId)
                .map(PaymentEntity::toDomain);
    }

    @Override
    public boolean claimForConfirmation(UUID id) {
        return repository.claimState(
                id.toString(),
                PaymentState.PENDING_CONFIRMATION,
                PaymentState.CONFIRMING,
                Instant.now(clock)
        ) == 1;
    }
    @Override
    public long count() {
        return repository.count();
    }
}
