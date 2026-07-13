package io.hoony.payment.infrastructure.memory;

import io.hoony.payment.application.port.out.CancellationRepository;
import io.hoony.payment.domain.cancellation.CancellationEvent;
import io.hoony.payment.domain.cancellation.PaymentCancellation;
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
public class InMemoryCancellationRepository implements CancellationRepository {

    private final ConcurrentMap<UUID, PaymentCancellation> cancellations = new ConcurrentHashMap<>();

    @Override
    public void save(PaymentCancellation cancellation) {
        cancellations.put(cancellation.id(), cancellation);
    }

    @Override
    public Optional<PaymentCancellation> findById(UUID id) {
        return Optional.ofNullable(cancellations.get(id));
    }

    @Override
    public long sumInFlightMinorUnits(UUID paymentId) {
        return cancellations.values().stream()
                .filter(cancellation -> cancellation.paymentId().equals(paymentId))
                .filter(cancellation -> cancellation.state().reservesAmount())
                .mapToLong(cancellation -> cancellation.amount().minorUnits())
                .sum();
    }

    @Override
    public boolean claimForConfirmation(UUID id) {
        PaymentCancellation cancellation = cancellations.get(id);
        if (cancellation == null) {
            return false;
        }
        synchronized (cancellation) {
            if (!cancellation.state().requiresConfirmation()) {
                return false;
            }
            cancellation.apply(CancellationEvent.CONFIRM_CANCEL_STARTED);
            save(cancellation);
            return true;
        }
    }

    @Override
    public List<PaymentCancellation> findAll() {
        return new ArrayList<>(cancellations.values());
    }
}