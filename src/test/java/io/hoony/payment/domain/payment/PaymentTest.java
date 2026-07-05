package io.hoony.payment.domain.payment;

import io.hoony.payment.domain.common.DomainException;
import io.hoony.payment.domain.money.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    void request_생성시_금액은_불변_승인대상으로_고정된다() {
        Money amount = Money.positiveKrw(30_000);
        Payment payment = Payment.request(UUID.randomUUID(), "user-1", "merchant-1", "order-1", amount);

        assertThat(payment.amount()).isSameAs(amount);
        assertThat(payment.state()).isEqualTo(PaymentState.REQUESTED);
    }

    @Test
    void apply_상태전이규칙을_통과한_이벤트만_반영한다() {
        Payment payment = Payment.request(
                UUID.randomUUID(),
                "user-1",
                "merchant-1",
                "order-1",
                Money.positiveKrw(30_000)
        );

        payment.apply(PaymentEvent.APPROVE_STARTED);
        payment.apply(PaymentEvent.APPROVE_SUCCEEDED);

        assertThat(payment.state()).isEqualTo(PaymentState.APPROVED);
    }

    @Test
    void recordCanceledAmount_승인금액을_초과하면_거부한다() {
        Payment payment = Payment.request(
                UUID.randomUUID(),
                "user-1",
                "merchant-1",
                "order-1",
                Money.positiveKrw(30_000)
        );
        payment.apply(PaymentEvent.APPROVE_STARTED);
        payment.apply(PaymentEvent.APPROVE_SUCCEEDED);

        payment.recordCanceledAmount(Money.positiveKrw(20_000));

        assertThatThrownBy(() -> payment.recordCanceledAmount(Money.positiveKrw(20_000)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("exceed");
    }
}