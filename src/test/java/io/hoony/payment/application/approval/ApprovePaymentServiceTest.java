package io.hoony.payment.application.approval;

import io.hoony.payment.application.port.out.IdempotencyRecordRepository;
import io.hoony.payment.application.port.out.PaymentAttemptRepository;
import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import io.hoony.payment.domain.money.Money;
import io.hoony.payment.domain.payment.PaymentState;
import io.hoony.payment.infrastructure.memory.InMemoryIdempotencyRecordRepository;
import io.hoony.payment.infrastructure.memory.InMemoryPaymentAttemptRepository;
import io.hoony.payment.infrastructure.memory.InMemoryPaymentRepository;
import io.hoony.payment.infrastructure.pg.FakePaymentGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovePaymentServiceTest {

    @Test
    void approve_동일멱등키동시요청은_pg승인을_한번만_호출한다() throws Exception {
        IdempotencyRecordRepository idempotencyRecords = new InMemoryIdempotencyRecordRepository();
        PaymentRepository payments = new InMemoryPaymentRepository();
        PaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
        FakePaymentGateway gateway = new FakePaymentGateway();
        ApprovePaymentService service = new ApprovePaymentService(
                idempotencyRecords,
                payments,
                attempts,
                gateway,
                Clock.systemUTC()
        );
        ApprovePaymentCommand command = new ApprovePaymentCommand(
                "approve-key-1",
                "user-1",
                "merchant-1",
                "order-1",
                Money.positiveKrw(30_000)
        );

        int requestCount = 100;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(requestCount);
        List<java.util.concurrent.Future<ApprovePaymentResult>> futures = java.util.stream.IntStream.range(0, requestCount)
                .mapToObj(ignored -> executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.approve(command);
                }))
                .toList();

        assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<ApprovePaymentResult> results = futures.stream()
                .map(future -> {
                    try {
                        return future.get(3, TimeUnit.SECONDS);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .toList();
        executor.shutdownNow();

        UUID paymentId = results.getFirst().paymentId();
        assertThat(results).allSatisfy(result -> {
            assertThat(result.paymentId()).isEqualTo(paymentId);
            assertThat(result.state()).isEqualTo(PaymentState.APPROVED);
        });
        assertThat(gateway.approveCallCount()).isEqualTo(1);
        assertThat(payments.count()).isEqualTo(1);
        assertThat(attempts.findAll()).hasSize(1);
    }

    @Test
    void approve_같은멱등키에_다른payload가_들어오면_거부한다() {
        ApprovePaymentService service = new ApprovePaymentService(
                new InMemoryIdempotencyRecordRepository(),
                new InMemoryPaymentRepository(),
                new InMemoryPaymentAttemptRepository(),
                new FakePaymentGateway(),
                Clock.systemUTC()
        );

        service.approve(new ApprovePaymentCommand(
                "approve-key-1",
                "user-1",
                "merchant-1",
                "order-1",
                Money.positiveKrw(30_000)
        ));

        assertThatThrownBy(() -> service.approve(new ApprovePaymentCommand(
                "approve-key-1",
                "user-1",
                "merchant-1",
                "order-1",
                Money.positiveKrw(40_000)
        )))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("different request fingerprint");
    }

    @Test
    void approve_다른멱등키라도_같은가맹점주문이면_중복승인을_거부한다() {
        FakePaymentGateway gateway = new FakePaymentGateway();
        ApprovePaymentService service = new ApprovePaymentService(
                new InMemoryIdempotencyRecordRepository(),
                new InMemoryPaymentRepository(),
                new InMemoryPaymentAttemptRepository(),
                gateway,
                Clock.systemUTC()
        );

        service.approve(new ApprovePaymentCommand(
                "approve-key-1",
                "user-1",
                "merchant-1",
                "order-1",
                Money.positiveKrw(30_000)
        ));

        assertThatThrownBy(() -> service.approve(new ApprovePaymentCommand(
                "approve-key-2",
                "user-1",
                "merchant-1",
                "order-1",
                Money.positiveKrw(30_000)
        )))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("already exists");
        assertThat(gateway.approveCallCount()).isEqualTo(1);
    }

    @Test
    void approve_같은key라도_가맹점이다르면_서로다른scope로_처리한다() {
        FakePaymentGateway gateway = new FakePaymentGateway();
        PaymentRepository payments = new InMemoryPaymentRepository();
        ApprovePaymentService service = new ApprovePaymentService(
                new InMemoryIdempotencyRecordRepository(),
                payments,
                new InMemoryPaymentAttemptRepository(),
                gateway,
                Clock.systemUTC()
        );

        service.approve(new ApprovePaymentCommand(
                "shared-key",
                "user-1",
                "merchant-1",
                "order-merchant-1",
                Money.positiveKrw(30_000)
        ));
        service.approve(new ApprovePaymentCommand(
                "shared-key",
                "user-2",
                "merchant-2",
                "order-merchant-2",
                Money.positiveKrw(30_000)
        ));

        assertThat(gateway.approveCallCount()).isEqualTo(2);
        assertThat(payments.count()).isEqualTo(2);
    }

    @Test
    void approve_처리중인멱등요청은_pg를다시호출하지않고_409대상으로_거부한다() {
        InMemoryIdempotencyRecordRepository idempotencyRecords =
                new InMemoryIdempotencyRecordRepository();
        FakePaymentGateway gateway = new FakePaymentGateway();
        ApprovePaymentService service = new ApprovePaymentService(
                idempotencyRecords,
                new InMemoryPaymentRepository(),
                new InMemoryPaymentAttemptRepository(),
                gateway,
                Clock.systemUTC()
        );
        ApprovePaymentCommand command = new ApprovePaymentCommand(
                "processing-key",
                "user-1",
                "merchant-1",
                "order-processing-1",
                Money.positiveKrw(30_000)
        );
        IdempotencyScope scope = new IdempotencyScope(
                command.merchantId(),
                IdempotencyOperation.APPROVE,
                command.idempotencyKey()
        );
        idempotencyRecords.save(IdempotencyRecord.start(
                scope,
                ApprovalRequestFingerprint.from(command),
                Instant.now()
        ));

        assertThatThrownBy(() -> service.approve(command))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("still processing");
        assertThat(gateway.approveCallCount()).isZero();
    }
}