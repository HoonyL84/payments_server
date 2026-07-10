package io.hoony.payment.application.confirmation;

import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.domain.attempt.PaymentAttemptResult;
import io.hoony.payment.domain.attempt.PaymentOperation;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.common.ResourceNotFoundException;
import io.hoony.payment.domain.payment.Payment;
import io.hoony.payment.domain.payment.PaymentEvent;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ConfirmPaymentService {

    private final PaymentRepository payments;
    private final PaymentAttemptRepository paymentAttempts;
    private final PaymentGateway paymentGateway;
    private final Clock clock;

    public ConfirmPaymentService(
            PaymentRepository payments,
            PaymentAttemptRepository paymentAttempts,
            PaymentGateway paymentGateway,
            Clock clock
    ) {
        this.payments = payments;
        this.paymentAttempts = paymentAttempts;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
    }

    public ConfirmPaymentResult confirm(UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));

        claimConfirmation(payment);

        PaymentGateway.ConfirmationResult result = paymentGateway.confirmApprove(
                new PaymentGateway.ConfirmationRequest(paymentId)
        );
        paymentAttempts.save(new PaymentAttempt(
                UUID.randomUUID(),
                payment.id(),
                null,
                PaymentOperation.CONFIRM_APPROVE,
                toAttemptResult(result.status()),
                now()
        ));

        synchronized (payment) {
            switch (result.status()) {
                case APPROVED -> payment.apply(PaymentEvent.CONFIRM_APPROVED);
                case DECLINED -> payment.apply(PaymentEvent.CONFIRM_FAILED);
                case UNKNOWN -> payment.apply(PaymentEvent.CONFIRM_UNKNOWN);
            }
            payments.save(payment);
            return new ConfirmPaymentResult(payment.id(), payment.state());
        }
    }

    private void claimConfirmation(Payment payment) {
        synchronized (payment) {
            if (!payment.state().requiresConfirmation()) {
                throw new ResourceConflictException(
                        "Payment does not require confirmation. state=" + payment.state()
                );
            }
            payment.apply(PaymentEvent.CONFIRM_STARTED);
            payments.save(payment);
        }
    }

    private PaymentAttemptResult toAttemptResult(PaymentGateway.ConfirmationStatus status) {
        return switch (status) {
            case APPROVED -> PaymentAttemptResult.SUCCEEDED;
            case DECLINED -> PaymentAttemptResult.FAILED;
            case UNKNOWN -> PaymentAttemptResult.UNKNOWN;
        };
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
