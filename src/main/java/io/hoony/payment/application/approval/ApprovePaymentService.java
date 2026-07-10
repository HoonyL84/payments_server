package io.hoony.payment.application.approval;

import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.domain.attempt.PaymentAttemptResult;
import io.hoony.payment.domain.attempt.PaymentOperation;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import io.hoony.payment.domain.payment.Payment;
import io.hoony.payment.domain.payment.PaymentEvent;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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

    public ApprovePaymentResult approve(ApprovePaymentCommand command) {
        String fingerprint = ApprovalRequestFingerprint.from(command);
        IdempotencyScope scope = new IdempotencyScope(
                command.merchantId(),
                IdempotencyOperation.APPROVE,
                command.idempotencyKey()
        );
        Object idempotencyLock = lockFor(idempotencyLocks, scope);
        Object orderLock = lockFor(orderLocks, orderKey(command));

        synchronized (idempotencyLock) {
            synchronized (orderLock) {
                return approveInsideGate(command, scope, fingerprint);
            }
        }
    }

    private ApprovePaymentResult approveInsideGate(
            ApprovePaymentCommand command,
            IdempotencyScope scope,
            String fingerprint
    ) {
        Optional<IdempotencyRecord> existingRecord = idempotencyRecords.findByScope(scope);
        if (existingRecord.isPresent()) {
            IdempotencyRecord record = existingRecord.orElseThrow();
            record.requireSameFingerprint(fingerprint);
            return record.responseBody()
                    .map(ApprovePaymentResult::fromStoredResponse)
                    .orElseThrow(() -> new ResourceConflictException("Payment approval is still processing."));
        }

        payments.findByMerchantIdAndOrderId(command.merchantId(), command.orderId())
                .ifPresent(existing -> {
                    throw new ResourceConflictException("Payment already exists for merchant order.");
                });

        IdempotencyRecord record = IdempotencyRecord.start(scope, fingerprint, now());
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

        PaymentGateway.ApprovalResult gatewayResult = paymentGateway.approve(
                new PaymentGateway.ApprovalRequest(
                        payment.id(),
                        command.merchantId(),
                        command.orderId(),
                        command.amount()
                )
        );
        paymentAttempts.save(new PaymentAttempt(
                UUID.randomUUID(),
                payment.id(),
                null,
                PaymentOperation.APPROVE,
                toAttemptResult(gatewayResult.status()),
                now()
        ));

        switch (gatewayResult.status()) {
            case APPROVED -> payment.apply(PaymentEvent.APPROVE_SUCCEEDED);
            case DECLINED -> payment.apply(PaymentEvent.APPROVE_FAILED);
            case TIMED_OUT -> payment.apply(PaymentEvent.APPROVE_TIMED_OUT);
        }
        payments.save(payment);

        ApprovePaymentResult result = new ApprovePaymentResult(payment.id(), payment.state(), false);
        record.complete(result.toStoredResponse());
        idempotencyRecords.save(record);
        return result;
    }

    private PaymentAttemptResult toAttemptResult(PaymentGateway.ApprovalStatus status) {
        return switch (status) {
            case APPROVED -> PaymentAttemptResult.SUCCEEDED;
            case DECLINED -> PaymentAttemptResult.FAILED;
            case TIMED_OUT -> PaymentAttemptResult.TIMED_OUT;
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

    private static Object lockFor(Object[] locks, Object key) {
        return locks[Math.floorMod(key.hashCode(), locks.length)];
    }

    private static String orderKey(ApprovePaymentCommand command) {
        return command.merchantId() + "|" + command.orderId();
    }
}
