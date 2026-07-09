package io.hoony.payment.application.approval;

import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.domain.attempt.PaymentAttemptResult;
import io.hoony.payment.domain.attempt.PaymentOperation;
import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import io.hoony.payment.domain.payment.Payment;
import io.hoony.payment.domain.payment.PaymentEvent;
import io.hoony.payment.infrastructure.pg.PgApproveRequest;
import io.hoony.payment.infrastructure.pg.PgApproveResult;
import io.hoony.payment.infrastructure.pg.PgApproveStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates idempotent payment approval processing.
 */
@Service
public class ApprovePaymentService {

    private static final int LOCK_STRIPES = 1024;

    private final IdempotencyRecordRepository idempotencyRecords;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository paymentAttempts;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final Object[] idempotencyLocks = createLocks();
    private final Object[] orderLocks = createLocks();

    /**
     * Creates the approval service.
     */
    public ApprovePaymentService(
            IdempotencyRecordRepository idempotencyRecords,
            PaymentRepository payments,
            PaymentAttemptRepository paymentAttempts,
            PaymentGateway paymentGateway,
            Clock clock
    ) {
        this.idempotencyRecords = idempotencyRecords;
        this.payments = payments;
        this.paymentAttempts = paymentAttempts;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
    }

    /**
     * Approves a payment once for the same idempotency key and fingerprint.
     */
    public ApprovePaymentResult approve(ApprovePaymentCommand command) {
        String fingerprint = ApprovalRequestFingerprint.from(command);
        Object idempotencyLock = lockFor(idempotencyLocks, command.idempotencyKey());
        Object orderLock = lockFor(orderLocks, orderKey(command));
        synchronized (idempotencyLock) {
            synchronized (orderLock) {
                return approveInsideGate(command, fingerprint);
            }
        }
    }

    private ApprovePaymentResult approveInsideGate(ApprovePaymentCommand command, String fingerprint) {
        IdempotencyRecord record = idempotencyRecords.findByKey(command.idempotencyKey())
                .map(existing -> {
                    existing.requireSameFingerprint(fingerprint);
                    return existing;
                })
                .orElseGet(() -> IdempotencyRecord.start(command.idempotencyKey(), fingerprint, now()));

        if (record.responseBody().isPresent()) {
            return ApprovePaymentResult.fromStoredResponse(record.responseBody().orElseThrow());
        }
        payments.findByMerchantIdAndOrderId(command.merchantId(), command.orderId())
                .ifPresent(existing -> {
                    throw new DomainException("Payment already exists for merchant order.");
                });

        Payment payment = Payment.request(
                UUID.randomUUID(),
                command.userId(),
                command.merchantId(),
                command.orderId(),
                command.amount()
        );
        payment.apply(PaymentEvent.APPROVE_STARTED);
        payments.save(payment);
        idempotencyRecords.save(record);

        PgApproveResult pgResult = paymentGateway.approve(new PgApproveRequest(
                payment.id(),
                command.merchantId(),
                command.orderId(),
                command.amount()
        ));
        paymentAttempts.save(new PaymentAttempt(
                UUID.randomUUID(),
                payment.id(),
                null,
                PaymentOperation.APPROVE,
                toAttemptResult(pgResult.status()),
                now()
        ));

        if (pgResult.status() == PgApproveStatus.APPROVED) {
            payment.apply(PaymentEvent.APPROVE_SUCCEEDED);
        } else {
            payment.apply(PaymentEvent.APPROVE_FAILED);
        }
        payments.save(payment);

        ApprovePaymentResult result = new ApprovePaymentResult(payment.id(), payment.state(), false);
        record.complete(result.toStoredResponse());
        idempotencyRecords.save(record);
        return result;
    }

    private PaymentAttemptResult toAttemptResult(PgApproveStatus status) {
        return switch (status) {
            case APPROVED -> PaymentAttemptResult.SUCCEEDED;
            case DECLINED -> PaymentAttemptResult.FAILED;
        };
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private static Object lockFor(Object[] locks, String key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }

    private static String orderKey(ApprovePaymentCommand command) {
        return command.merchantId() + "|" + command.orderId();
    }
}