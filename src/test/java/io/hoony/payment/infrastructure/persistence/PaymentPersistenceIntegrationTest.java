package io.hoony.payment.infrastructure.persistence;

import io.hoony.payment.application.approval.ApprovalPreparation;
import io.hoony.payment.application.approval.ApprovalRequestFingerprint;
import io.hoony.payment.application.approval.ApprovalTransactionService;
import io.hoony.payment.application.approval.ApprovePaymentCommand;
import io.hoony.payment.application.approval.ApprovePaymentResult;
import io.hoony.payment.application.approval.ApprovePaymentService;
import io.hoony.payment.application.cancellation.CancelPaymentCommand;
import io.hoony.payment.application.cancellation.CancelPaymentResult;
import io.hoony.payment.application.cancellation.CancelPaymentService;
import io.hoony.payment.application.cancellation.CancellationConfirmationPreparation;
import io.hoony.payment.application.cancellation.CancellationPreparation;
import io.hoony.payment.application.cancellation.CancellationRequestFingerprint;
import io.hoony.payment.application.cancellation.CancellationTransactionService;
import io.hoony.payment.application.ledger.LedgerConsistencyService;
import io.hoony.payment.application.confirmation.ConfirmationPreparation;
import io.hoony.payment.application.port.out.CancellationRepository;
import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.application.port.out.LedgerEntryRepository;
import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.attempt.PaymentAttemptResult;
import io.hoony.payment.domain.cancellation.CancellationState;
import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import io.hoony.payment.domain.ledger.LedgerAccount;
import io.hoony.payment.domain.ledger.LedgerDirection;
import io.hoony.payment.domain.ledger.LedgerEntry;
import io.hoony.payment.domain.ledger.LedgerEntryType;
import io.hoony.payment.domain.money.Money;
import io.hoony.payment.domain.outbox.OutboxEventType;
import io.hoony.payment.domain.payment.PaymentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration")
@SpringBootTest
@Import(PaymentPersistenceIntegrationTest.GatewayTestConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.flyway.enabled=true",
        "spring.data.redis.repositories.enabled=false"
})
class PaymentPersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    @Autowired
    private ApprovePaymentService approvePaymentService;

    @Autowired
    private ApprovalTransactionService transactions;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private CancelPaymentService cancelPaymentService;

    @Autowired
    private CancellationTransactionService cancellationTransactions;

    @Autowired
    private CancellationRepository cancellations;

    @Autowired
    private PaymentAttemptRepository attempts;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecords;

    @Autowired
    private LedgerEntryRepository ledgerEntries;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private LedgerConsistencyService ledgerConsistencyService;

    @Autowired
    private CheckingPaymentGateway gateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM payment_attempts");
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM payment_cancellations");
        jdbcTemplate.update("DELETE FROM payments");
        gateway.reset();
    }

    @Test
    void approve_성공결과를_attempt_원장_outbox와함께저장한다() {
        ApprovePaymentResult result = approvePaymentService.approve(command(
                "integration-key-1",
                "integration-order-1"
        ));

        assertThat(result.state()).isEqualTo(PaymentState.APPROVED);
        assertThat(gateway.approveCallCount()).isEqualTo(1);
        assertThat(gateway.transactionActiveDuringApprove()).isFalse();
        assertThat(attempts.findAll()).singleElement().satisfies(attempt -> {
            assertThat(attempt.result()).isEqualTo(PaymentAttemptResult.SUCCEEDED);
            assertThat(attempt.providerRequestId()).startsWith("approve-");
            assertThat(attempt.providerTransactionId()).startsWith("pg-txn-");
            assertThat(attempt.completedAt()).isNotNull();
        });
        assertThat(ledgerEntries.findByPaymentId(result.paymentId())).hasSize(2);
        assertThat(ledgerConsistencyService.findDrifts()).isEmpty();
        assertThat(outboxEvents.findAll()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(OutboxEventType.PAYMENT_APPROVED);
            assertThat(event.aggregateId()).isEqualTo(result.paymentId());
        });
    }

    @Test
    void approve_후처리트랜잭션이실패하면_상태_attempt_outbox가부분반영되지않는다() {
        ApprovePaymentCommand command = command("integration-key-2", "integration-order-2");
        IdempotencyScope scope = scope(command);
        ApprovalPreparation preparation = transactions.prepare(
                command,
                scope,
                ApprovalRequestFingerprint.from(command)
        );

        UUID groupId = deterministicId("approval-group:" + preparation.payment().id());
        ledgerEntries.saveAll(List.of(new LedgerEntry(
                UUID.randomUUID(),
                groupId,
                preparation.payment().id(),
                null,
                LedgerEntryType.APPROVAL,
                LedgerAccount.PG_CLEARING,
                LedgerDirection.DEBIT,
                preparation.payment().amount(),
                Instant.now()
        )));

        assertThatThrownBy(() -> transactions.complete(
                scope,
                preparation.payment().id(),
                preparation.attempt().id(),
                PaymentGateway.ApprovalResult.approved("pg-txn-rollback")
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(payments.findById(preparation.payment().id()).orElseThrow().state())
                .isEqualTo(PaymentState.APPROVING);
        assertThat(attempts.findById(preparation.attempt().id()).orElseThrow().result())
                .isEqualTo(PaymentAttemptResult.PROCESSING);
        assertThat(idempotencyRecords.findByScope(scope).orElseThrow().responseBody()).isEmpty();
        assertThat(outboxEvents.findAll()).isEmpty();
        assertThat(ledgerEntries.findByPaymentId(preparation.payment().id())).hasSize(1);
    }

    @Test
    void approve_동일요청을동시에보내도_payment와_pg호출은하나로수렴한다() throws Exception {
        ApprovePaymentCommand command = command(
                "integration-concurrent-key",
                "integration-concurrent-order"
        );
        int requestCount = 20;
        var executor = Executors.newFixedThreadPool(requestCount);

        try {
            var futures = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        try {
                            approvePaymentService.approve(command);
                            return null;
                        } catch (RuntimeException exception) {
                            return exception;
                        }
                    }))
                    .toList();

            List<RuntimeException> failures = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(failures).allSatisfy(
                    failure -> assertThat(failure).isInstanceOf(ResourceConflictException.class)
            );
            assertThat(payments.count()).isEqualTo(1);
            assertThat(gateway.approveCallCount()).isEqualTo(1);
            assertThat(attempts.findAll()).hasSize(1);
            assertThat(ledgerEntries.findAll()).hasSize(2);
            assertThat(outboxEvents.findAll()).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void confirm_동시에선점해도_한요청만상태를차지하고_승인결과를원장과함께저장한다() throws Exception {
        ApprovePaymentCommand command = command(
                "integration-confirm-key",
                "integration-confirm-order"
        );
        IdempotencyScope scope = scope(command);
        ApprovalPreparation approval = transactions.prepare(
                command,
                scope,
                ApprovalRequestFingerprint.from(command)
        );
        transactions.complete(
                scope,
                approval.payment().id(),
                approval.attempt().id(),
                PaymentGateway.ApprovalResult.timedOut()
        );
        assertThat(ledgerEntries.findByPaymentId(approval.payment().id())).isEmpty();

        int requestCount = 10;
        var executor = Executors.newFixedThreadPool(requestCount);
        ConfirmationPreparation claimed;
        try {
            var futures = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        try {
                            return transactions.prepareConfirmation(approval.payment().id());
                        } catch (ResourceConflictException exception) {
                            return null;
                        }
                    }))
                    .toList();
            List<ConfirmationPreparation> claims = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertThat(claims).hasSize(1);
            claimed = claims.getFirst();
        } finally {
            executor.shutdownNow();
        }

        transactions.completeConfirmation(
                claimed,
                PaymentGateway.ConfirmationResult.approved("pg-confirmed-txn")
        );

        assertThat(payments.findById(approval.payment().id()).orElseThrow().state())
                .isEqualTo(PaymentState.APPROVED);
        assertThat(attempts.findAll()).hasSize(2);
        assertThat(ledgerEntries.findByPaymentId(approval.payment().id())).hasSize(2);
        assertThat(ledgerConsistencyService.findDrifts()).isEmpty();
        assertThat(outboxEvents.findAll())
                .extracting(event -> event.type())
                .containsExactlyInAnyOrder(
                        OutboxEventType.PAYMENT_PENDING_CONFIRMATION,
                        OutboxEventType.PAYMENT_APPROVED
                );
    }
    @Test
    void cancel_성공결과를_누적금액_역분개원장_outbox와함께저장한다() {
        ApprovePaymentResult approval = approvePaymentService.approve(command(
                "cancel-approval-key-1",
                "cancel-order-1"
        ));

        CancelPaymentResult result = cancelPaymentService.cancel(cancelCommand(
                "cancel-key-1",
                approval.paymentId(),
                10_000
        ));

        assertThat(result.state()).isEqualTo(CancellationState.CANCELED);
        assertThat(result.reused()).isFalse();
        assertThat(gateway.cancelCallCount()).isEqualTo(1);
        assertThat(gateway.transactionActiveDuringCancel()).isFalse();
        assertThat(gateway.lastOriginalProviderTransactionId()).startsWith("pg-txn-");
        assertThat(payments.findById(approval.paymentId()).orElseThrow().canceledAmount())
                .isEqualTo(Money.krw(10_000));
        assertThat(cancellations.findAll()).singleElement().satisfies(cancellation -> {
            assertThat(cancellation.id()).isEqualTo(result.cancellationId());
            assertThat(cancellation.state()).isEqualTo(CancellationState.CANCELED);
        });
        assertThat(ledgerEntries.findByPaymentId(approval.paymentId()))
                .filteredOn(entry -> entry.type() == LedgerEntryType.CANCELLATION)
                .hasSize(2);
        assertThat(ledgerConsistencyService.findDrifts()).isEmpty();
        assertThat(outboxEvents.findAll())
                .extracting(event -> event.type())
                .contains(OutboxEventType.PAYMENT_APPROVED, OutboxEventType.PAYMENT_CANCELED);
    }

    @Test
    void cancel_후처리트랜잭션이실패하면_누적금액과취소상태가부분반영되지않는다() {
        ApprovePaymentResult approval = approvePaymentService.approve(command(
                "cancel-rollback-approval-key",
                "cancel-rollback-order"
        ));
        CancelPaymentCommand command = cancelCommand(
                "cancel-rollback-key",
                approval.paymentId(),
                10_000
        );
        CancellationPreparation preparation = cancellationTransactions.prepare(
                command,
                CancellationRequestFingerprint.from(command)
        );

        UUID groupId = deterministicId("cancellation-group:" + preparation.cancellation().id());
        ledgerEntries.saveAll(List.of(new LedgerEntry(
                UUID.randomUUID(),
                groupId,
                approval.paymentId(),
                preparation.cancellation().id(),
                LedgerEntryType.CANCELLATION,
                LedgerAccount.MERCHANT_PAYABLE,
                LedgerDirection.DEBIT,
                command.amount(),
                Instant.now()
        )));

        assertThatThrownBy(() -> cancellationTransactions.complete(
                preparation,
                PaymentGateway.CancellationResult.canceled("cancel-rollback-txn")
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(payments.findById(approval.paymentId()).orElseThrow().canceledAmount())
                .isEqualTo(Money.krw(0));
        assertThat(cancellations.findById(preparation.cancellation().id()).orElseThrow().state())
                .isEqualTo(CancellationState.CANCELING);
        assertThat(attempts.findById(preparation.attempt().id()).orElseThrow().result())
                .isEqualTo(PaymentAttemptResult.PROCESSING);
        assertThat(idempotencyRecords.findByScope(preparation.scope()).orElseThrow().responseBody())
                .isEmpty();
        assertThat(outboxEvents.findAll())
                .extracting(event -> event.type())
                .doesNotContain(OutboxEventType.PAYMENT_CANCELED);
    }
    @Test
    void cancel_명확한실패에는_역분개없이_실패이벤트만저장한다() {
        ApprovePaymentResult approval = approvePaymentService.approve(command(
                "cancel-declined-approval-key",
                "cancel-declined-order"
        ));
        gateway.nextCancellationStatus(PaymentGateway.CancellationStatus.DECLINED);

        CancelPaymentResult result = cancelPaymentService.cancel(cancelCommand(
                "cancel-declined-key",
                approval.paymentId(),
                10_000
        ));

        assertThat(result.state()).isEqualTo(CancellationState.CANCEL_FAILED);
        assertThat(payments.findById(approval.paymentId()).orElseThrow().canceledAmount())
                .isEqualTo(Money.krw(0));
        assertThat(ledgerEntries.findByPaymentId(approval.paymentId()))
                .filteredOn(entry -> entry.type() == LedgerEntryType.CANCELLATION)
                .isEmpty();
        assertThat(outboxEvents.findAll())
                .extracting(event -> event.type())
                .contains(OutboxEventType.PAYMENT_CANCEL_FAILED);
    }
    @Test
    void cancel_동일멱등키는_기존응답을재사용하고_pg를다시호출하지않는다() {
        ApprovePaymentResult approval = approvePaymentService.approve(command(
                "cancel-approval-key-2",
                "cancel-order-2"
        ));
        CancelPaymentCommand command = cancelCommand("cancel-key-2", approval.paymentId(), 10_000);

        CancelPaymentResult first = cancelPaymentService.cancel(command);
        CancelPaymentResult replay = cancelPaymentService.cancel(command);

        assertThat(replay.cancellationId()).isEqualTo(first.cancellationId());
        assertThat(replay.reused()).isTrue();
        assertThat(gateway.cancelCallCount()).isEqualTo(1);
        assertThatThrownBy(() -> cancelPaymentService.cancel(cancelCommand(
                "cancel-key-2",
                approval.paymentId(),
                20_000
        ))).isInstanceOf(ResourceConflictException.class);
    }

    @Test
    void cancel_동일멱등키를동시에보내도_pg호출과역분개는하나로수렴한다() throws Exception {
        ApprovePaymentResult approval = approvePaymentService.approve(command(
                "cancel-same-key-approval",
                "cancel-same-key-order"
        ));
        CancelPaymentCommand command = cancelCommand(
                "cancel-same-concurrent-key",
                approval.paymentId(),
                10_000
        );
        var executor = Executors.newFixedThreadPool(20);

        try {
            var futures = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(ignored -> executor.submit(() -> cancelAttempt(command)))
                    .toList();
            List<Object> outcomes = futures.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(outcomes).anySatisfy(
                    outcome -> assertThat(outcome).isInstanceOf(CancelPaymentResult.class)
            );
            assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome)
                    .isInstanceOfAny(CancelPaymentResult.class, ResourceConflictException.class));
            assertThat(gateway.cancelCallCount()).isEqualTo(1);
            assertThat(cancellations.findAll()).hasSize(1);
            assertThat(payments.findById(approval.paymentId()).orElseThrow().canceledAmount())
                    .isEqualTo(Money.krw(10_000));
            assertThat(ledgerEntries.findByPaymentId(approval.paymentId()))
                    .filteredOn(entry -> entry.type() == LedgerEntryType.CANCELLATION)
                    .hasSize(2);
            assertThat(outboxEvents.findAll())
                    .filteredOn(event -> event.type() == OutboxEventType.PAYMENT_CANCELED)
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    void cancel_동시부분취소는_승인금액을초과하지않는다() throws Exception {
        ApprovePaymentResult approval = approvePaymentService.approve(command(
                "cancel-approval-key-3",
                "cancel-order-3"
        ));
        var executor = Executors.newFixedThreadPool(2);

        try {
            var futures = List.of(
                    executor.submit(() -> cancelAttempt(cancelCommand(
                            "cancel-concurrent-key-1",
                            approval.paymentId(),
                            20_000
                    ))),
                    executor.submit(() -> cancelAttempt(cancelCommand(
                            "cancel-concurrent-key-2",
                            approval.paymentId(),
                            20_000
                    )))
            );
            List<Object> outcomes = futures.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(outcomes).filteredOn(CancelPaymentResult.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(DomainException.class::isInstance).hasSize(1);
            assertThat(gateway.cancelCallCount()).isEqualTo(1);
            assertThat(payments.findById(approval.paymentId()).orElseThrow().canceledAmount())
                    .isEqualTo(Money.krw(20_000));
            assertThat(ledgerEntries.findByPaymentId(approval.paymentId()))
                    .filteredOn(entry -> entry.type() == LedgerEntryType.CANCELLATION)
                    .hasSize(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancel_timeout은_confirm_한요청만선점하고_확정후역분개한다() throws Exception {
        ApprovePaymentResult approval = approvePaymentService.approve(command(
                "cancel-approval-key-4",
                "cancel-order-4"
        ));
        gateway.nextCancellationStatus(PaymentGateway.CancellationStatus.TIMED_OUT);
        CancelPaymentResult pending = cancelPaymentService.cancel(cancelCommand(
                "cancel-key-4",
                approval.paymentId(),
                10_000
        ));

        assertThat(pending.state()).isEqualTo(CancellationState.CANCEL_PENDING_CONFIRMATION);
        assertThat(payments.findById(approval.paymentId()).orElseThrow().canceledAmount())
                .isEqualTo(Money.krw(0));
        assertThat(ledgerEntries.findByPaymentId(approval.paymentId()))
                .filteredOn(entry -> entry.type() == LedgerEntryType.CANCELLATION)
                .isEmpty();
        assertThat(outboxEvents.findAll())
                .extracting(event -> event.type())
                .contains(OutboxEventType.PAYMENT_CANCEL_PENDING_CONFIRMATION);

        var executor = Executors.newFixedThreadPool(10);
        CancellationConfirmationPreparation claimed;
        try {
            var futures = java.util.stream.IntStream.range(0, 10)
                    .mapToObj(ignored -> executor.submit(() -> {
                        try {
                            return cancellationTransactions.prepareConfirmation(
                                    approval.paymentId(),
                                    pending.cancellationId()
                            );
                        } catch (ResourceConflictException exception) {
                            return null;
                        }
                    }))
                    .toList();
            List<CancellationConfirmationPreparation> claims = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertThat(claims).hasSize(1);
            claimed = claims.getFirst();
        } finally {
            executor.shutdownNow();
        }

        cancellationTransactions.completeConfirmation(
                claimed,
                PaymentGateway.CancellationConfirmationResult.canceled("confirmed-cancel-txn")
        );

        assertThat(cancellations.findById(pending.cancellationId()).orElseThrow().state())
                .isEqualTo(CancellationState.CANCELED);
        assertThat(payments.findById(approval.paymentId()).orElseThrow().canceledAmount())
                .isEqualTo(Money.krw(10_000));
        assertThat(ledgerEntries.findByPaymentId(approval.paymentId()))
                .filteredOn(entry -> entry.type() == LedgerEntryType.CANCELLATION)
                .hasSize(2);
        assertThat(ledgerConsistencyService.findDrifts()).isEmpty();
    }

    private Object cancelAttempt(CancelPaymentCommand command) {
        try {
            return cancelPaymentService.cancel(command);
        } catch (DomainException exception) {
            return exception;
        }
    }

    private static CancelPaymentCommand cancelCommand(
            String key,
            UUID paymentId,
            long amountMinorUnits
    ) {
        return new CancelPaymentCommand(key, paymentId, Money.positiveKrw(amountMinorUnits));
    }
    private static ApprovePaymentCommand command(String key, String orderId) {
        return new ApprovePaymentCommand(
                key,
                "integration-user",
                "merchant-1",
                orderId,
                Money.positiveKrw(30_000)
        );
    }

    private static IdempotencyScope scope(ApprovePaymentCommand command) {
        return new IdempotencyScope(
                command.merchantId(),
                IdempotencyOperation.APPROVE,
                command.idempotencyKey()
        );
    }

    private static UUID deterministicId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    @TestConfiguration
    static class GatewayTestConfiguration {

        @Bean
        @Primary
        CheckingPaymentGateway checkingPaymentGateway() {
            return new CheckingPaymentGateway();
        }
    }

    static final class CheckingPaymentGateway implements PaymentGateway {

        private final AtomicInteger approveCallCount = new AtomicInteger();
        private final AtomicInteger cancelCallCount = new AtomicInteger();
        private final AtomicInteger confirmCancelCallCount = new AtomicInteger();
        private final AtomicBoolean transactionActiveDuringApprove = new AtomicBoolean();
        private final AtomicBoolean transactionActiveDuringCancel = new AtomicBoolean();
        private volatile CancellationStatus nextCancellationStatus = CancellationStatus.CANCELED;
        private volatile String lastOriginalProviderTransactionId;
        private volatile CancellationConfirmationStatus nextCancellationConfirmationStatus =
                CancellationConfirmationStatus.CANCELED;

        @Override
        public ApprovalResult approve(ApprovalRequest request) {
            approveCallCount.incrementAndGet();
            transactionActiveDuringApprove.set(
                    TransactionSynchronizationManager.isActualTransactionActive()
            );
            return ApprovalResult.approved("pg-txn-" + request.providerRequestId());
        }

        @Override
        public ConfirmationResult confirmApprove(ConfirmationRequest request) {
            return ConfirmationResult.approved("pg-confirmed-txn-" + request.providerRequestId());
        }

        @Override
        public CancellationResult cancel(CancellationRequest request) {
            cancelCallCount.incrementAndGet();
            lastOriginalProviderTransactionId = request.originalProviderTransactionId();
            transactionActiveDuringCancel.set(
                    TransactionSynchronizationManager.isActualTransactionActive()
            );
            return switch (nextCancellationStatus) {
                case CANCELED -> CancellationResult.canceled(
                        "pg-cancel-txn-" + request.providerRequestId()
                );
                case DECLINED -> CancellationResult.declined("DECLINED");
                case TIMED_OUT -> CancellationResult.timedOut();
            };
        }

        @Override
        public CancellationConfirmationResult confirmCancel(CancellationConfirmationRequest request) {
            confirmCancelCallCount.incrementAndGet();
            return switch (nextCancellationConfirmationStatus) {
                case CANCELED -> CancellationConfirmationResult.canceled(
                        "pg-confirmed-cancel-txn-" + request.providerRequestId()
                );
                case DECLINED -> CancellationConfirmationResult.declined("DECLINED");
                case UNKNOWN -> CancellationConfirmationResult.unknown();
            };
        }
        int cancelCallCount() {
            return cancelCallCount.get();
        }

        int confirmCancelCallCount() {
            return confirmCancelCallCount.get();
        }

        String lastOriginalProviderTransactionId() {
            return lastOriginalProviderTransactionId;
        }
        boolean transactionActiveDuringCancel() {
            return transactionActiveDuringCancel.get();
        }

        void nextCancellationStatus(CancellationStatus status) {
            nextCancellationStatus = status;
        }

        void nextCancellationConfirmationStatus(CancellationConfirmationStatus status) {
            nextCancellationConfirmationStatus = status;
        }
        int approveCallCount() {
            return approveCallCount.get();
        }

        boolean transactionActiveDuringApprove() {
            return transactionActiveDuringApprove.get();
        }

        void reset() {
            approveCallCount.set(0);
            cancelCallCount.set(0);
            confirmCancelCallCount.set(0);
            transactionActiveDuringApprove.set(false);
            transactionActiveDuringCancel.set(false);
            nextCancellationStatus = CancellationStatus.CANCELED;
            nextCancellationConfirmationStatus = CancellationConfirmationStatus.CANCELED;
            lastOriginalProviderTransactionId = null;
        }
    }
}
