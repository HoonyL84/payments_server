package io.hoony.payment.infrastructure.persistence.repository;

import io.hoony.payment.domain.payment.PaymentState;
import io.hoony.payment.infrastructure.persistence.entity.PaymentEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface JpaPaymentEntityRepository extends JpaRepository<PaymentEntity, String> {

    Optional<PaymentEntity> findByMerchantIdAndOrderId(String merchantId, String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from PaymentEntity payment where payment.id = :id")
    Optional<PaymentEntity> findByIdForUpdate(@Param("id") String id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PaymentEntity payment
               set payment.state = :nextState,
                   payment.version = payment.version + 1,
                   payment.updatedAt = :updatedAt
             where payment.id = :id
               and payment.state = :expectedState
            """)
    int claimState(
            @Param("id") String id,
            @Param("expectedState") PaymentState expectedState,
            @Param("nextState") PaymentState nextState,
            @Param("updatedAt") Instant updatedAt
    );
}