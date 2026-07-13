package io.hoony.payment.infrastructure.persistence.repository;

import io.hoony.payment.infrastructure.persistence.entity.PaymentAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaPaymentAttemptEntityRepository
        extends JpaRepository<PaymentAttemptEntity, String> {
}
