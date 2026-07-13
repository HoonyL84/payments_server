package io.hoony.payment.infrastructure.persistence.adapter;

import io.hoony.payment.application.port.out.CancellationRepository;
import io.hoony.payment.domain.cancellation.CancellationState;
import io.hoony.payment.domain.cancellation.PaymentCancellation;
import io.hoony.payment.infrastructure.persistence.entity.PaymentCancellationEntity;
import io.hoony.payment.infrastructure.persistence.repository.JpaPaymentCancellationEntityRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Profile("!test")
@Repository
public class JpaCancellationRepositoryAdapter implements CancellationRepository {

    private static final List<CancellationState> IN_FLIGHT_STATES = List.of(
            CancellationState.CANCELING,
            CancellationState.CANCEL_PENDING_CONFIRMATION,
            CancellationState.CANCEL_CONFIRMING
    );

    private final JpaPaymentCancellationEntityRepository repository;
    private final Clock clock;

    public JpaCancellationRepositoryAdapter(
            JpaPaymentCancellationEntityRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void save(PaymentCancellation cancellation) {
        Instant now = Instant.now(clock);
        PaymentCancellationEntity entity = repository.findById(cancellation.id().toString())
                .orElseGet(() -> new PaymentCancellationEntity(cancellation, now));
        entity.update(cancellation, now);
        repository.saveAndFlush(entity);
    }

    @Override
    public Optional<PaymentCancellation> findById(UUID id) {
        return repository.findById(id.toString()).map(PaymentCancellationEntity::toDomain);
    }

    @Override
    public long sumInFlightMinorUnits(UUID paymentId) {
        return repository.sumAmountByPaymentIdAndStates(paymentId.toString(), IN_FLIGHT_STATES);
    }

    @Override
    public boolean claimForConfirmation(UUID id) {
        return repository.claimState(
                id.toString(),
                CancellationState.CANCEL_PENDING_CONFIRMATION,
                CancellationState.CANCEL_CONFIRMING,
                Instant.now(clock)
        ) == 1;
    }

    @Override
    public List<PaymentCancellation> findAll() {
        return repository.findAll().stream().map(PaymentCancellationEntity::toDomain).toList();
    }
}