package io.hoony.payment.infrastructure.persistence;

import io.hoony.payment.application.approval.ApprovalPreparation;
import io.hoony.payment.application.approval.ApprovalRequestFingerprint;
import io.hoony.payment.application.approval.ApprovalTransactionService;
import io.hoony.payment.application.approval.ApprovePaymentCommand;
import io.hoony.payment.application.approval.ApprovePaymentResult;
import io.hoony.payment.application.approval.ApprovePaymentService;
import io.hoony.payment.application.ledger.LedgerConsistencyService;
import io.hoony.payment.application.confirmation.ConfirmationPreparation;
import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.application.port.out.LedgerEntryRepository;
import io.hoony.payment.application.port.out.OutboxEventRepository;
import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.attempt.PaymentAttemptResult;
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
                PaymentGateway.ConfirmationResult.approved()
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
        private final AtomicBoolean transactionActiveDuringApprove = new AtomicBoolean();

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
            return ConfirmationResult.approved();
        }

        int approveCallCount() {
            return approveCallCount.get();
        }

        boolean transactionActiveDuringApprove() {
            return transactionActiveDuringApprove.get();
        }

        void reset() {
            approveCallCount.set(0);
            transactionActiveDuringApprove.set(false);
        }
    }
}
