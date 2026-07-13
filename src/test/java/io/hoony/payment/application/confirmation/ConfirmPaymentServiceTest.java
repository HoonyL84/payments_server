package io.hoony.payment.application.confirmation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hoony.payment.application.approval.ApprovePaymentCommand;
import io.hoony.payment.application.approval.ApprovePaymentResult;
import io.hoony.payment.application.approval.ApprovePaymentService;
import io.hoony.payment.application.approval.ApprovalTransactionService;
import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.application.query.GetPaymentService;
import io.hoony.payment.domain.attempt.PaymentAttemptResult;
import io.hoony.payment.domain.attempt.PaymentOperation;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.money.Money;
import io.hoony.payment.domain.payment.PaymentState;
import io.hoony.payment.infrastructure.memory.InMemoryIdempotencyRecordRepository;
import io.hoony.payment.infrastructure.memory.InMemoryPaymentAttemptRepository;
import io.hoony.payment.infrastructure.memory.InMemoryLedgerEntryRepository;
import io.hoony.payment.infrastructure.memory.InMemoryMerchantContractRepository;
import io.hoony.payment.infrastructure.memory.InMemoryOutboxEventRepository;
import io.hoony.payment.infrastructure.memory.InMemoryPaymentRepository;
import io.hoony.payment.infrastructure.pg.FakePaymentGateway;
import io.hoony.payment.infrastructure.pg.PgApproveStatus;
import io.hoony.payment.infrastructure.pg.PgConfirmApproveStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmPaymentServiceTest {

    @Test
    void approve_pgTimeout이면_pendingConfirmation으로_저장한다() {
        TestContext context = new TestContext();
        context.gateway.nextApproveStatus(PgApproveStatus.TIMED_OUT);

        ApprovePaymentResult result = context.approvePayment("approve-timeout-key-1", "order-timeout-1");

        assertThat(result.state()).isEqualTo(PaymentState.PENDING_CONFIRMATION);
        assertThat(context.payments.findById(result.paymentId()).orElseThrow().state())
                .isEqualTo(PaymentState.PENDING_CONFIRMATION);
        assertThat(context.attempts.findAll()).singleElement().satisfies(attempt -> {
            assertThat(attempt.operation()).isEqualTo(PaymentOperation.APPROVE);
            assertThat(attempt.result()).isEqualTo(PaymentAttemptResult.TIMED_OUT);
        });
    }

    @Test
    void confirm_pg승인확정이면_pendingPayment를_approved로_수렴시킨다() {
        TestContext context = new TestContext();
        context.gateway.nextApproveStatus(PgApproveStatus.TIMED_OUT);
        UUID paymentId = context.approvePayment("approve-timeout-key-2", "order-timeout-2").paymentId();
        context.gateway.nextConfirmApproveStatus(PgConfirmApproveStatus.APPROVED);

        ConfirmPaymentResult result = context.confirmPaymentService.confirm(paymentId);

        assertThat(result.state()).isEqualTo(PaymentState.APPROVED);
        assertThat(context.gateway.confirmApproveCallCount()).isEqualTo(1);
        assertThat(context.attempts.findAll()).anySatisfy(attempt -> {
            assertThat(attempt.operation()).isEqualTo(PaymentOperation.CONFIRM_APPROVE);
            assertThat(attempt.result()).isEqualTo(PaymentAttemptResult.SUCCEEDED);
        });
    }

    @Test
    void confirm_pg거절확정이면_pendingPayment를_failed로_수렴시킨다() {
        TestContext context = new TestContext();
        context.gateway.nextApproveStatus(PgApproveStatus.TIMED_OUT);
        UUID paymentId = context.approvePayment("approve-timeout-key-3", "order-timeout-3").paymentId();
        context.gateway.nextConfirmApproveStatus(PgConfirmApproveStatus.DECLINED);

        ConfirmPaymentResult result = context.confirmPaymentService.confirm(paymentId);

        assertThat(result.state()).isEqualTo(PaymentState.FAILED);
        assertThat(context.attempts.findAll()).anySatisfy(attempt -> {
            assertThat(attempt.operation()).isEqualTo(PaymentOperation.CONFIRM_APPROVE);
            assertThat(attempt.result()).isEqualTo(PaymentAttemptResult.FAILED);
        });
    }

    @Test
    void confirm_pg결과가아직불명확하면_pending상태로_돌아간다() {
        TestContext context = new TestContext();
        context.gateway.nextApproveStatus(PgApproveStatus.TIMED_OUT);
        UUID paymentId = context.approvePayment("approve-timeout-key-4", "order-timeout-4").paymentId();
        context.gateway.nextConfirmApproveStatus(PgConfirmApproveStatus.UNKNOWN);

        ConfirmPaymentResult result = context.confirmPaymentService.confirm(paymentId);

        assertThat(result.state()).isEqualTo(PaymentState.PENDING_CONFIRMATION);
        assertThat(context.attempts.findAll()).anySatisfy(attempt -> {
            assertThat(attempt.operation()).isEqualTo(PaymentOperation.CONFIRM_APPROVE);
            assertThat(attempt.result()).isEqualTo(PaymentAttemptResult.UNKNOWN);
        });
    }

    @Test
    void approve_timeout이후_confirm되어도_멱등재시도는_최초응답을_재사용한다() {
        TestContext context = new TestContext();
        context.gateway.nextApproveStatus(PgApproveStatus.TIMED_OUT);
        UUID paymentId = context.approvePayment("approve-timeout-key-5", "order-timeout-5").paymentId();
        context.gateway.nextConfirmApproveStatus(PgConfirmApproveStatus.APPROVED);
        context.confirmPaymentService.confirm(paymentId);

        ApprovePaymentResult replayed = context.approvePayment("approve-timeout-key-5", "order-timeout-5");

        assertThat(replayed.paymentId()).isEqualTo(paymentId);
        assertThat(replayed.state()).isEqualTo(PaymentState.PENDING_CONFIRMATION);
        assertThat(replayed.reused()).isTrue();
        assertThat(context.getPaymentService.get(paymentId).state()).isEqualTo(PaymentState.APPROVED);
        assertThat(context.gateway.approveCallCount()).isEqualTo(1);
    }

    @Test
    void confirm_pending상태가아니면_충돌로_거부한다() {
        TestContext context = new TestContext();
        UUID paymentId = context.approvePayment("approve-success-key-1", "order-success-1").paymentId();

        assertThatThrownBy(() -> context.confirmPaymentService.confirm(paymentId))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("does not require confirmation");
        assertThat(context.gateway.confirmApproveCallCount()).isZero();
    }

    @Test
    void confirm_동일payment동시실행은_pgConfirm을_한번만_호출한다() throws Exception {
        BlockingPaymentGateway gateway = new BlockingPaymentGateway();
        TestContext context = new TestContext(gateway);
        gateway.delegate.nextApproveStatus(PgApproveStatus.TIMED_OUT);
        UUID paymentId = context.approvePayment("approve-timeout-key-6", "order-timeout-6").paymentId();

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> context.confirmPaymentService.confirm(paymentId));
            assertThat(gateway.confirmEntered.await(3, TimeUnit.SECONDS)).isTrue();

            var second = executor.submit(() -> context.confirmPaymentService.confirm(paymentId));
            assertThatThrownBy(() -> second.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResourceConflictException.class);

            gateway.confirmRelease.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS).state()).isEqualTo(PaymentState.APPROVED);
            assertThat(gateway.delegate.confirmApproveCallCount()).isEqualTo(1);
        } finally {
            gateway.confirmRelease.countDown();
            executor.shutdownNow();
        }
    }

    private static final class TestContext {

        private final IdempotencyRecordRepository idempotencyRecords = new InMemoryIdempotencyRecordRepository();
        private final PaymentRepository payments = new InMemoryPaymentRepository();
        private final PaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
        private final FakePaymentGateway gateway;
        private final ApprovePaymentService approvePaymentService;
        private final ConfirmPaymentService confirmPaymentService;
        private final GetPaymentService getPaymentService;

        private TestContext() {
            this(new FakePaymentGateway());
        }

        private TestContext(PaymentGateway paymentGateway) {
            this.gateway = paymentGateway instanceof BlockingPaymentGateway blocking
                    ? blocking.delegate
                    : (FakePaymentGateway) paymentGateway;
            ApprovalTransactionService transactions = new ApprovalTransactionService(
                    idempotencyRecords,
                    payments,
                    attempts,
                    new InMemoryMerchantContractRepository(),
                    new InMemoryLedgerEntryRepository(),
                    new InMemoryOutboxEventRepository(),
                    new ObjectMapper(),
                    Clock.systemUTC()
            );
            this.approvePaymentService = new ApprovePaymentService(transactions, paymentGateway);
            this.confirmPaymentService = new ConfirmPaymentService(transactions, paymentGateway);
            this.getPaymentService = new GetPaymentService(payments);
        }

        private ApprovePaymentResult approvePayment(String idempotencyKey, String orderId) {
            return approvePaymentService.approve(new ApprovePaymentCommand(
                    idempotencyKey,
                    "user-1",
                    "merchant-1",
                    orderId,
                    Money.positiveKrw(30_000)
            ));
        }
    }

    private static final class BlockingPaymentGateway implements PaymentGateway {

        private final FakePaymentGateway delegate = new FakePaymentGateway();
        private final CountDownLatch confirmEntered = new CountDownLatch(1);
        private final CountDownLatch confirmRelease = new CountDownLatch(1);

        @Override
        public ApprovalResult approve(ApprovalRequest request) {
            return delegate.approve(request);
        }

        @Override
        public ConfirmationResult confirmApprove(ConfirmationRequest request) {
            confirmEntered.countDown();
            try {
                if (!confirmRelease.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release confirm.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Confirm wait interrupted.", exception);
            }
            return delegate.confirmApprove(request);
        }

        @Override
        public CancellationResult cancel(CancellationRequest request) {
            return delegate.cancel(request);
        }

        @Override
        public CancellationConfirmationResult confirmCancel(CancellationConfirmationRequest request) {
            return delegate.confirmCancel(request);
        }
    }
}
