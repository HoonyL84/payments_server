package io.hoony.payment.infrastructure.persistence.repository;

import io.hoony.payment.domain.attempt.PaymentAttemptResult;
import io.hoony.payment.domain.attempt.PaymentOperation;
import io.hoony.payment.infrastructure.persistence.entity.PaymentAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaPaymentAttemptEntityRepository
        extends JpaRepository<PaymentAttemptEntity, String> {

    Optional<PaymentAttemptEntity> findFirstByPaymentIdAndOperationAndResultOrderByCompletedAtDesc(
            String paymentId,
            PaymentOperation operation,
            PaymentAttemptResult result
    );
}