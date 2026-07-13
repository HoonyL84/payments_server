package io.hoony.payment.infrastructure.persistence.repository;

import io.hoony.payment.domain.cancellation.CancellationState;
import io.hoony.payment.infrastructure.persistence.entity.PaymentCancellationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;

public interface JpaPaymentCancellationEntityRepository
        extends JpaRepository<PaymentCancellationEntity, String> {

    @Query("""
            select coalesce(sum(cancellation.amountMinorUnits), 0)
              from PaymentCancellationEntity cancellation
             where cancellation.paymentId = :paymentId
               and cancellation.state in :states
            """)
    long sumAmountByPaymentIdAndStates(
            @Param("paymentId") String paymentId,
            @Param("states") Collection<CancellationState> states
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PaymentCancellationEntity cancellation
               set cancellation.state = :nextState,
                   cancellation.version = cancellation.version + 1,
                   cancellation.updatedAt = :updatedAt
             where cancellation.id = :id
               and cancellation.state = :expectedState
            """)
    int claimState(
            @Param("id") String id,
            @Param("expectedState") CancellationState expectedState,
            @Param("nextState") CancellationState nextState,
            @Param("updatedAt") Instant updatedAt
    );
}