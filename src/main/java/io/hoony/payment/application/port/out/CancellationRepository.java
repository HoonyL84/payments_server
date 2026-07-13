package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.cancellation.PaymentCancellation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CancellationRepository {

    void save(PaymentCancellation cancellation);

    Optional<PaymentCancellation> findById(UUID id);

    long sumInFlightMinorUnits(UUID paymentId);

    boolean claimForConfirmation(UUID id);

    List<PaymentCancellation> findAll();
}