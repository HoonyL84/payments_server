package io.hoony.payment.application.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.application.port.out.LedgerEntryRepository;
import io.hoony.payment.application.port.out.MerchantContractRepository;
import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.application.confirmation.ConfirmPaymentResult;
import io.hoony.payment.application.confirmation.ConfirmationPreparation;
import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.domain.attempt.PaymentAttemptResult;
import io.hoony.payment.domain.attempt.PaymentOperation;
import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.common.ResourceNotFoundException;
import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import io.hoony.payment.domain.ledger.LedgerAccount;
import io.hoony.payment.domain.ledger.LedgerDirection;
import io.hoony.payment.domain.ledger.LedgerEntry;
import io.hoony.payment.domain.ledger.LedgerEntryType;
import io.hoony.payment.domain.merchant.MerchantContract;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxEventType;
import io.hoony.payment.domain.payment.Payment;
import io.hoony.payment.domain.payment.PaymentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ApprovalTransactionService {

    private final IdempotencyRecordRepository idempotencyRecords;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository paymentAttempts;
    private final MerchantContractRepository merchants;
    private final LedgerEntryRepository ledgerEntries;
    private final OutboxEventRepository outboxEvents;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApprovalTransactionService(
            IdempotencyRecordRepository idempotencyRecords,
            PaymentRepository payments,
            PaymentAttemptRepository paymentAttempts,
            MerchantContractRepository merchants,
            LedgerEntryRepository ledgerEntries,
            OutboxEventRepository outboxEvents,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.idempotencyRecords = idempotencyRecords;
        this.payments = payments;
        this.paymentAttempts = paymentAttempts;
        this.merchants = merchants;
        this.ledgerEntries = ledgerEntries;
        this.outboxEvents = outboxEvents;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ApprovalPreparation prepare(
            ApprovePaymentCommand command,
            IdempotencyScope scope,
            String fingerprint
    ) {
        IdempotencyRecord existingRecord = idempotencyRecords.findByScope(scope).orElse(null);
        if (existingRecord != null) {
            existingRecord.requireSameFingerprint(fingerprint);
            return existingRecord.responseBody()
                    .map(ApprovePaymentResult::fromStoredResponse)
                    .map(ApprovalPreparation::replay)
                    .orElseThrow(() -> new ResourceConflictException(
                            "Payment approval is still processing."
                    ));
        }

        MerchantContract merchant = merchants.findById(command.merchantId())
                .orElseThrow(() -> new ResourceNotFoundException("Merchant not found."));
        merchant.requireActive();

        payments.findByMerchantIdAndOrderId(command.merchantId(), command.orderId())
                .ifPresent(existing -> {
                    throw new ResourceConflictException(
                            "Payment already exists for merchant order."
                    );
                });

        Instant now = now();
        Payment payment = Payment.request(
                UUID.randomUUID(),
                command.userId(),
                command.merchantId(),
                command.orderId(),
                command.amount()
        );
        payment.apply(PaymentEvent.APPROVE_STARTED);

        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = PaymentAttempt.start(
                attemptId,
                payment.id(),
                null,
                PaymentOperation.APPROVE,
                merchant.provider(),
                "approve-" + attemptId,
                now
        );

        payments.save(payment);
        idempotencyRecords.save(IdempotencyRecord.start(scope, fingerprint, now));
        paymentAttempts.save(attempt);
        return ApprovalPreparation.newApproval(
                payment,
                attempt,
                merchant.provider(),
                merchant.routingKey()
        );
    }

    @Transactional
    public ApprovePaymentResult complete(
            IdempotencyScope scope,
            UUID paymentId,
            UUID attemptId,
            PaymentGateway.ApprovalResult gatewayResult
    ) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        PaymentAttempt attempt = paymentAttempts.findById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found."));
        IdempotencyRecord record = idempotencyRecords.findByScope(scope)
                .orElseThrow(() -> new ResourceNotFoundException("Idempotency record not found."));

        Instant completedAt = now();
        PaymentAttemptResult attemptResult;
        OutboxEventType eventType;

        switch (gatewayResult.status()) {
            case APPROVED -> {
                payment.apply(PaymentEvent.APPROVE_SUCCEEDED);
                attemptResult = PaymentAttemptResult.SUCCEEDED;
                eventType = OutboxEventType.PAYMENT_APPROVED;
            }
            case DECLINED -> {
                payment.apply(PaymentEvent.APPROVE_FAILED);
                attemptResult = PaymentAttemptResult.FAILED;
                eventType = OutboxEventType.PAYMENT_FAILED;
            }
            case TIMED_OUT -> {
                payment.apply(PaymentEvent.APPROVE_TIMED_OUT);
                attemptResult = PaymentAttemptResult.TIMED_OUT;
                eventType = OutboxEventType.PAYMENT_PENDING_CONFIRMATION;
            }
            default -> throw new DomainException("Unsupported PG approval result.");
        }

        attempt.complete(
                attemptResult,
                gatewayResult.providerTransactionId(),
                gatewayResult.errorCode(),
                completedAt
        );
        ApprovePaymentResult result = new ApprovePaymentResult(payment.id(), payment.state(), false);
        record.complete(result.toStoredResponse());

        payments.save(payment);
        paymentAttempts.save(attempt);
        idempotencyRecords.save(record);

        if (gatewayResult.status() == PaymentGateway.ApprovalStatus.APPROVED) {
            ledgerEntries.saveAll(approvalLedger(payment, completedAt));
        }

        outboxEvents.save(OutboxEvent.pending(
                deterministicId("outbox:" + eventType + ":" + attempt.id()),
                payment.id(),
                eventType,
                outboxPayload(payment),
                completedAt
        ));
        return result;
    }

    @Transactional
    public ConfirmationPreparation prepareConfirmation(UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        if (!payment.state().requiresConfirmation()) {
            throw new ResourceConflictException(
                    "Payment does not require confirmation. state=" + payment.state()
            );
        }

        MerchantContract merchant = merchants.findById(payment.merchantId())
                .orElseThrow(() -> new ResourceNotFoundException("Merchant not found."));
        merchant.requireActive();

        if (!payments.claimForConfirmation(paymentId)) {
            throw new ResourceConflictException("Payment confirmation is already claimed.");
        }

        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = PaymentAttempt.start(
                attemptId,
                paymentId,
                null,
                PaymentOperation.CONFIRM_APPROVE,
                merchant.provider(),
                "confirm-approve-" + attemptId,
                now()
        );
        paymentAttempts.save(attempt);
        return new ConfirmationPreparation(
                paymentId,
                attemptId,
                merchant.provider(),
                merchant.routingKey(),
                attempt.providerRequestId()
        );
    }

    @Transactional
    public ConfirmPaymentResult completeConfirmation(
            ConfirmationPreparation preparation,
            PaymentGateway.ConfirmationResult gatewayResult
    ) {
        Payment payment = payments.findById(preparation.paymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        PaymentAttempt attempt = paymentAttempts.findById(preparation.attemptId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment attempt not found."));

        Instant completedAt = now();
        PaymentAttemptResult attemptResult;
        OutboxEventType eventType;

        switch (gatewayResult.status()) {
            case APPROVED -> {
                payment.apply(PaymentEvent.CONFIRM_APPROVED);
                attemptResult = PaymentAttemptResult.SUCCEEDED;
                eventType = OutboxEventType.PAYMENT_APPROVED;
            }
            case DECLINED -> {
                payment.apply(PaymentEvent.CONFIRM_FAILED);
                attemptResult = PaymentAttemptResult.FAILED;
                eventType = OutboxEventType.PAYMENT_FAILED;
            }
            case UNKNOWN -> {
                payment.apply(PaymentEvent.CONFIRM_UNKNOWN);
                attemptResult = PaymentAttemptResult.UNKNOWN;
                eventType = OutboxEventType.PAYMENT_PENDING_CONFIRMATION;
            }
            default -> throw new DomainException("Unsupported PG confirmation result.");
        }

        attempt.complete(
                attemptResult,
                gatewayResult.providerTransactionId(),
                gatewayResult.errorCode(),
                completedAt
        );
        payments.save(payment);
        paymentAttempts.save(attempt);

        if (gatewayResult.status() == PaymentGateway.ConfirmationStatus.APPROVED) {
            ledgerEntries.saveAll(approvalLedger(payment, completedAt));
        }

        outboxEvents.save(OutboxEvent.pending(
                deterministicId("outbox:" + eventType + ":" + attempt.id()),
                payment.id(),
                eventType,
                outboxPayload(payment),
                completedAt
        ));
        return new ConfirmPaymentResult(payment.id(), payment.state());
    }
    private List<LedgerEntry> approvalLedger(Payment payment, Instant recordedAt) {
        UUID groupId = deterministicId("approval-group:" + payment.id());
        return List.of(
                new LedgerEntry(
                        deterministicId(groupId + ":PG_CLEARING:DEBIT"),
                        groupId,
                        payment.id(),
                        null,
                        LedgerEntryType.APPROVAL,
                        LedgerAccount.PG_CLEARING,
                        LedgerDirection.DEBIT,
                        payment.amount(),
                        recordedAt
                ),
                new LedgerEntry(
                        deterministicId(groupId + ":MERCHANT_PAYABLE:CREDIT"),
                        groupId,
                        payment.id(),
                        null,
                        LedgerEntryType.APPROVAL,
                        LedgerAccount.MERCHANT_PAYABLE,
                        LedgerDirection.CREDIT,
                        payment.amount(),
                        recordedAt
                )
        );
    }

    private String outboxPayload(Payment payment) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "paymentId", payment.id().toString(),
                    "merchantId", payment.merchantId(),
                    "orderId", payment.orderId(),
                    "state", payment.state().name(),
                    "amountMinorUnits", payment.amount().minorUnits(),
                    "currency", payment.amount().currency()
            ));
        } catch (JsonProcessingException exception) {
            throw new DomainException("Failed to serialize payment outbox event.");
        }
    }

    private static UUID deterministicId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
