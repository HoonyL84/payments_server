package io.hoony.payment.application.cancellation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hoony.payment.application.port.out.CancellationRepository;
import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.application.port.out.LedgerEntryRepository;
import io.hoony.payment.application.port.out.MerchantContractRepository;
import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.attempt.PaymentAttempt;
import io.hoony.payment.domain.attempt.PaymentAttemptResult;
import io.hoony.payment.domain.attempt.PaymentOperation;
import io.hoony.payment.domain.cancellation.CancellationEvent;
import io.hoony.payment.domain.cancellation.PaymentCancellation;
import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.common.ResourceNotFoundException;
import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import io.hoony.payment.domain.ledger.LedgerAccount;
import io.hoony.payment.domain.ledger.LedgerDirection;
import io.hoony.payment.domain.ledger.LedgerEntry;
import io.hoony.payment.domain.ledger.LedgerEntryType;
import io.hoony.payment.domain.merchant.MerchantContract;
import io.hoony.payment.domain.money.Money;
import io.hoony.payment.domain.outbox.OutboxEvent;
import io.hoony.payment.domain.outbox.OutboxEventType;
import io.hoony.payment.domain.payment.Payment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class CancellationTransactionService {

    private final PaymentRepository payments;
    private final CancellationRepository cancellations;
    private final PaymentAttemptRepository attempts;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final MerchantContractRepository merchants;
    private final LedgerEntryRepository ledgerEntries;
    private final OutboxEventRepository outboxEvents;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CancellationTransactionService(
            PaymentRepository payments,
            CancellationRepository cancellations,
            PaymentAttemptRepository attempts,
            IdempotencyRecordRepository idempotencyRecords,
            MerchantContractRepository merchants,
            LedgerEntryRepository ledgerEntries,
            OutboxEventRepository outboxEvents,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.payments = payments;
        this.cancellations = cancellations;
        this.attempts = attempts;
        this.idempotencyRecords = idempotencyRecords;
        this.merchants = merchants;
        this.ledgerEntries = ledgerEntries;
        this.outboxEvents = outboxEvents;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CancellationPreparation prepare(CancelPaymentCommand command, String fingerprint) {
        Payment payment = payments.findByIdForUpdate(command.paymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        IdempotencyScope scope = new IdempotencyScope(
                payment.merchantId(),
                IdempotencyOperation.CANCEL,
                command.idempotencyKey()
        );

        IdempotencyRecord existingRecord = idempotencyRecords.findByScope(scope).orElse(null);
        if (existingRecord != null) {
            existingRecord.requireSameFingerprint(fingerprint);
            return existingRecord.responseBody()
                    .map(CancelPaymentResult::fromStoredResponse)
                    .map(CancellationPreparation::replay)
                    .orElseThrow(() -> new ResourceConflictException("Payment cancellation is still processing."));
        }

        MerchantContract merchant = merchants.findById(payment.merchantId())
                .orElseThrow(() -> new ResourceNotFoundException("Merchant not found."));
        merchant.requireActive();

        long inFlightMinorUnits = cancellations.sumInFlightMinorUnits(payment.id());
        payment.requireCancellationCapacity(
                command.amount(),
                new Money(inFlightMinorUnits, payment.amount().currency())
        );

        PaymentAttempt approvalAttempt = latestApprovalAttempt(payment.id())
                .orElseThrow(() -> new ResourceConflictException("Approved PG transaction was not found."));
        if (approvalAttempt.providerTransactionId() == null
                || approvalAttempt.providerTransactionId().isBlank()) {
            throw new ResourceConflictException("Approved PG transaction id was not found.");
        }

        Instant now = now();
        UUID cancellationId = UUID.randomUUID();
        PaymentCancellation cancellation = PaymentCancellation.request(
                cancellationId,
                payment.id(),
                command.amount()
        );
        cancellation.requireSameCurrency(payment.amount());
        cancellation.apply(CancellationEvent.CANCEL_STARTED);

        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = PaymentAttempt.start(
                attemptId,
                payment.id(),
                cancellationId,
                PaymentOperation.CANCEL,
                approvalAttempt.provider(),
                originalCancelRequestId(cancellationId),
                now
        );

        cancellations.save(cancellation);
        idempotencyRecords.save(IdempotencyRecord.start(scope, fingerprint, now));
        attempts.save(attempt);

        return CancellationPreparation.newCancellation(
                scope,
                payment,
                cancellation,
                attempt,
                approvalAttempt.provider(),
                merchant.routingKey(),
                approvalAttempt.providerTransactionId()
        );
    }

    @Transactional
    public CancelPaymentResult complete(
            CancellationPreparation preparation,
            PaymentGateway.CancellationResult gatewayResult
    ) {
        Payment payment = payments.findByIdForUpdate(preparation.payment().id())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        PaymentCancellation cancellation = cancellations.findById(preparation.cancellation().id())
                .orElseThrow(() -> new ResourceNotFoundException("Cancellation not found."));
        PaymentAttempt attempt = attempts.findById(preparation.attempt().id())
                .orElseThrow(() -> new ResourceNotFoundException("Cancellation attempt not found."));
        IdempotencyRecord record = idempotencyRecords.findByScope(preparation.scope())
                .orElseThrow(() -> new ResourceNotFoundException("Idempotency record not found."));

        Instant completedAt = now();
        PaymentAttemptResult attemptResult;
        OutboxEventType eventType;

        switch (gatewayResult.status()) {
            case CANCELED -> {
                cancellation.apply(CancellationEvent.CANCEL_SUCCEEDED);
                payment.recordCanceledAmount(cancellation.amount());
                attemptResult = PaymentAttemptResult.SUCCEEDED;
                eventType = OutboxEventType.PAYMENT_CANCELED;
            }
            case DECLINED -> {
                cancellation.apply(CancellationEvent.CANCEL_FAILED);
                attemptResult = PaymentAttemptResult.FAILED;
                eventType = OutboxEventType.PAYMENT_CANCEL_FAILED;
            }
            case TIMED_OUT -> {
                cancellation.apply(CancellationEvent.CANCEL_TIMED_OUT);
                attemptResult = PaymentAttemptResult.TIMED_OUT;
                eventType = OutboxEventType.PAYMENT_CANCEL_PENDING_CONFIRMATION;
            }
            default -> throw new DomainException("Unsupported PG cancellation result.");
        }

        attempt.complete(
                attemptResult,
                gatewayResult.providerTransactionId(),
                gatewayResult.errorCode(),
                completedAt
        );
        CancelPaymentResult result = new CancelPaymentResult(
                payment.id(),
                cancellation.id(),
                cancellation.state(),
                false
        );
        record.complete(result.toStoredResponse());

        if (gatewayResult.status() == PaymentGateway.CancellationStatus.CANCELED) {
            payments.save(payment);
            ledgerEntries.saveAll(reversalLedger(payment, cancellation, completedAt));
        }
        cancellations.save(cancellation);
        attempts.save(attempt);
        idempotencyRecords.save(record);
        outboxEvents.save(outbox(payment, cancellation, eventType, attempt.id(), completedAt));
        return result;
    }

    @Transactional
    public CancellationConfirmationPreparation prepareConfirmation(UUID paymentId, UUID cancellationId) {
        PaymentCancellation cancellation = cancellations.findById(cancellationId)
                .orElseThrow(() -> new ResourceNotFoundException("Cancellation not found."));
        if (!cancellation.paymentId().equals(paymentId)) {
            throw new ResourceNotFoundException("Cancellation not found for payment.");
        }
        if (!cancellation.state().requiresConfirmation()) {
            throw new ResourceConflictException(
                    "Cancellation does not require confirmation. state=" + cancellation.state()
            );
        }

        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        MerchantContract merchant = merchants.findById(payment.merchantId())
                .orElseThrow(() -> new ResourceNotFoundException("Merchant not found."));
        merchant.requireActive();

        if (!cancellations.claimForConfirmation(cancellationId)) {
            throw new ResourceConflictException("Cancellation confirmation is already claimed.");
        }

        UUID attemptId = UUID.randomUUID();
        PaymentAttempt attempt = PaymentAttempt.start(
                attemptId,
                paymentId,
                cancellationId,
                PaymentOperation.CONFIRM_CANCEL,
                merchant.provider(),
                "confirm-cancel-" + attemptId,
                now()
        );
        attempts.save(attempt);
        return new CancellationConfirmationPreparation(
                paymentId,
                cancellationId,
                attemptId,
                merchant.provider(),
                merchant.routingKey(),
                originalCancelRequestId(cancellationId)
        );
    }

    @Transactional
    public ConfirmCancellationResult completeConfirmation(
            CancellationConfirmationPreparation preparation,
            PaymentGateway.CancellationConfirmationResult gatewayResult
    ) {
        Payment payment = payments.findByIdForUpdate(preparation.paymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        PaymentCancellation cancellation = cancellations.findById(preparation.cancellationId())
                .orElseThrow(() -> new ResourceNotFoundException("Cancellation not found."));
        PaymentAttempt attempt = attempts.findById(preparation.attemptId())
                .orElseThrow(() -> new ResourceNotFoundException("Cancellation attempt not found."));

        Instant completedAt = now();
        PaymentAttemptResult attemptResult;
        OutboxEventType eventType;

        switch (gatewayResult.status()) {
            case CANCELED -> {
                cancellation.apply(CancellationEvent.CONFIRM_CANCELED);
                payment.recordCanceledAmount(cancellation.amount());
                attemptResult = PaymentAttemptResult.SUCCEEDED;
                eventType = OutboxEventType.PAYMENT_CANCELED;
            }
            case DECLINED -> {
                cancellation.apply(CancellationEvent.CONFIRM_CANCEL_FAILED);
                attemptResult = PaymentAttemptResult.FAILED;
                eventType = OutboxEventType.PAYMENT_CANCEL_FAILED;
            }
            case UNKNOWN -> {
                cancellation.apply(CancellationEvent.CONFIRM_CANCEL_UNKNOWN);
                attemptResult = PaymentAttemptResult.UNKNOWN;
                eventType = OutboxEventType.PAYMENT_CANCEL_PENDING_CONFIRMATION;
            }
            default -> throw new DomainException("Unsupported PG cancellation confirmation result.");
        }

        attempt.complete(
                attemptResult,
                gatewayResult.providerTransactionId(),
                gatewayResult.errorCode(),
                completedAt
        );

        if (gatewayResult.status() == PaymentGateway.CancellationConfirmationStatus.CANCELED) {
            payments.save(payment);
            ledgerEntries.saveAll(reversalLedger(payment, cancellation, completedAt));
        }
        cancellations.save(cancellation);
        attempts.save(attempt);
        outboxEvents.save(outbox(payment, cancellation, eventType, attempt.id(), completedAt));
        return new ConfirmCancellationResult(payment.id(), cancellation.id(), cancellation.state());
    }

    private Optional<PaymentAttempt> latestApprovalAttempt(UUID paymentId) {
        return Stream.of(
                        attempts.findLatestSuccessful(paymentId, PaymentOperation.APPROVE),
                        attempts.findLatestSuccessful(paymentId, PaymentOperation.CONFIRM_APPROVE)
                )
                .flatMap(Optional::stream)
                .max(Comparator.comparing(PaymentAttempt::completedAt));
    }

    private List<LedgerEntry> reversalLedger(
            Payment payment,
            PaymentCancellation cancellation,
            Instant recordedAt
    ) {
        UUID groupId = deterministicId("cancellation-group:" + cancellation.id());
        return List.of(
                new LedgerEntry(
                        deterministicId(groupId + ":MERCHANT_PAYABLE:DEBIT"),
                        groupId,
                        payment.id(),
                        cancellation.id(),
                        LedgerEntryType.CANCELLATION,
                        LedgerAccount.MERCHANT_PAYABLE,
                        LedgerDirection.DEBIT,
                        cancellation.amount(),
                        recordedAt
                ),
                new LedgerEntry(
                        deterministicId(groupId + ":PG_CLEARING:CREDIT"),
                        groupId,
                        payment.id(),
                        cancellation.id(),
                        LedgerEntryType.CANCELLATION,
                        LedgerAccount.PG_CLEARING,
                        LedgerDirection.CREDIT,
                        cancellation.amount(),
                        recordedAt
                )
        );
    }

    private OutboxEvent outbox(
            Payment payment,
            PaymentCancellation cancellation,
            OutboxEventType eventType,
            UUID attemptId,
            Instant createdAt
    ) {
        return OutboxEvent.pending(
                deterministicId("outbox:" + eventType + ":" + attemptId),
                payment.id(),
                eventType,
                outboxPayload(payment, cancellation),
                createdAt
        );
    }

    private String outboxPayload(Payment payment, PaymentCancellation cancellation) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "paymentId", payment.id().toString(),
                    "cancellationId", cancellation.id().toString(),
                    "merchantId", payment.merchantId(),
                    "state", cancellation.state().name(),
                    "amountMinorUnits", cancellation.amount().minorUnits(),
                    "currency", cancellation.amount().currency()
            ));
        } catch (JsonProcessingException exception) {
            throw new DomainException("Failed to serialize cancellation outbox event.");
        }
    }

    private static String originalCancelRequestId(UUID cancellationId) {
        return "cancel-" + cancellationId;
    }

    private static UUID deterministicId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private Instant now() {
        return Instant.now(clock);
    }
}