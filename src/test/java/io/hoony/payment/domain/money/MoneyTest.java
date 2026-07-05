package io.hoony.payment.domain.money;

import io.hoony.payment.domain.common.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void krw_정수_minor_unit으로_금액을_생성한다() {
        Money money = Money.krw(10_000);

        assertThat(money.minorUnits()).isEqualTo(10_000);
        assertThat(money.currency()).isEqualTo("KRW");
    }

    @Test
    void positiveKrw_금액이_0이면_거부한다() {
        assertThatThrownBy(() -> Money.positiveKrw(0))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void minus_결과가_음수가_되면_거부한다() {
        Money money = Money.krw(1_000);

        assertThatThrownBy(() -> money.minus(Money.krw(2_000)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("negative");
    }
}