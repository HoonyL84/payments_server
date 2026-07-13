package io.hoony.payment.application.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.hoony.payment.domain.idempotency.IdempotencyRecord;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import io.hoony.payment.domain.ledger.LedgerDirection;
import io.hoony.payment.domain.money.Money;
import io.hoony.payment.domain.outbox.OutboxEventType;
import io.hoony.payment.domain.payment.PaymentState;
import io.hoony.payment.infrastructure.memory.InMemoryIdempotencyRecordRepository;
import io.hoony.payment.infrastructure.memory.InMemoryLedgerEntryRepository;
import io.hoony.payment.infrastructure.memory.InMemoryMerchantContractRepository;
import io.hoony.payment.infrastructure.memory.InMemoryOutboxEventRepository;
import io.hoony.payment.infrastructure.memory.InMemoryPaymentAttemptRepository;
import io.hoony.payment.infrastructure.memory.InMemoryPaymentRepository;
import io.hoony.payment.infrastructure.pg.FakePaymentGateway;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovePaymentServiceTest {

    @Test
    void approve_동일한요청을재시도하면_최초응답을재사용한다() {
        Fixture fixture = new Fixture();
        ApprovePaymentCommand command = command(
                "approve-key-1",
                "merchant-1",
                "order-1",
                30_000
        );

        ApprovePaymentResult first = fixture.service.approve(command);
        ApprovePaymentResult replay = fixture.service.approve(command);

        assertThat(replay.paymentId()).isEqualTo(first.paymentId());
        assertThat(replay.state()).isEqualTo(PaymentState.APPROVED);
        assertThat(replay.reused()).isTrue();
        assertThat(fixture.gateway.approveCallCount()).isEqualTo(1);
        assertThat(fixture.payments.count()).isEqualTo(1);
        assertThat(fixture.attempts.findAll()).hasSize(1);
    }

    @Test
    void approve_같은멱등키에_다른payload가_들어오면_거부한다() {
        Fixture fixture = new Fixture();
        fixture.service.approve(command("approve-key-1", "merchant-1", "order-1", 30_000));

        assertThatThrownBy(() -> fixture.service.approve(
                command("approve-key-1", "merchant-1", "order-1", 40_000)
        ))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("different request fingerprint");
    }

    @Test
    void approve_다른멱등키라도_같은가맹점주문이면_중복승인을_거부한다() {
        Fixture fixture = new Fixture();
        fixture.service.approve(command("approve-key-1", "merchant-1", "order-1", 30_000));

        assertThatThrownBy(() -> fixture.service.approve(
                command("approve-key-2", "merchant-1", "order-1", 30_000)
        ))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("already exists");
        assertThat(fixture.gateway.approveCallCount()).isEqualTo(1);
    }

    @Test
    void approve_같은key라도_가맹점이다르면_서로다른scope로_처리한다() {
        Fixture fixture = new Fixture();

        fixture.service.approve(command(
                "shared-key",
                "merchant-1",
                "order-merchant-1",
                30_000
        ));
        fixture.service.approve(command(
                "shared-key",
                "merchant-2",
                "order-merchant-2",
                30_000
        ));

        assertThat(fixture.gateway.approveCallCount()).isEqualTo(2);
        assertThat(fixture.payments.count()).isEqualTo(2);
    }

    @Test
    void approve_처리중인멱등요청은_pg를다시호출하지않고_거부한다() {
        Fixture fixture = new Fixture();
        ApprovePaymentCommand command = command(
                "processing-key",
                "merchant-1",
                "order-processing-1",
                30_000
        );
        IdempotencyScope scope = new IdempotencyScope(
                command.merchantId(),
                IdempotencyOperation.APPROVE,
                command.idempotencyKey()
        );
        fixture.idempotencyRecords.save(IdempotencyRecord.start(
                scope,
                ApprovalRequestFingerprint.from(command),
                Instant.now()
        ));

        assertThatThrownBy(() -> fixture.service.approve(command))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("still processing");
        assertThat(fixture.gateway.approveCallCount()).isZero();
    }

    @Test
    void approve_성공하면_균형원장과_outbox를기록한다() {
        Fixture fixture = new Fixture();

        ApprovePaymentResult result = fixture.service.approve(
                command("approve-key-1", "merchant-1", "order-1", 30_000)
        );

        var ledger = fixture.ledgerEntries.findByPaymentId(result.paymentId());
        assertThat(ledger).hasSize(2);
        assertThat(ledger)
                .filteredOn(entry -> entry.direction() == LedgerDirection.DEBIT)
                .extracting(entry -> entry.amount().minorUnits())
                .containsExactly(30_000L);
        assertThat(ledger)
                .filteredOn(entry -> entry.direction() == LedgerDirection.CREDIT)
                .extracting(entry -> entry.amount().minorUnits())
                .containsExactly(30_000L);
        assertThat(fixture.outboxEvents.findAll())
                .singleElement()
                .extracting(event -> event.type())
                .isEqualTo(OutboxEventType.PAYMENT_APPROVED);
    }

    private static ApprovePaymentCommand command(
            String key,
            String merchantId,
            String orderId,
            long amount
    ) {
        return new ApprovePaymentCommand(
                key,
                "user-1",
                merchantId,
                orderId,
                Money.positiveKrw(amount)
        );
    }

    private static final class Fixture {

        private final Clock clock = Clock.systemUTC();
        private final InMemoryIdempotencyRecordRepository idempotencyRecords =
                new InMemoryIdempotencyRecordRepository();
        private final InMemoryPaymentRepository payments = new InMemoryPaymentRepository();
        private final InMemoryPaymentAttemptRepository attempts =
                new InMemoryPaymentAttemptRepository();
        private final InMemoryLedgerEntryRepository ledgerEntries =
                new InMemoryLedgerEntryRepository();
        private final InMemoryOutboxEventRepository outboxEvents =
                new InMemoryOutboxEventRepository();
        private final FakePaymentGateway gateway = new FakePaymentGateway();
        private final ApprovalTransactionService transactions = new ApprovalTransactionService(
                idempotencyRecords,
                payments,
                attempts,
                new InMemoryMerchantContractRepository(),
                ledgerEntries,
                outboxEvents,
                new ObjectMapper(),
                clock
        );
        private final ApprovePaymentService service =
                new ApprovePaymentService(transactions, gateway);
    }
}
