package io.hoony.payment.domain.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    @Test
    void transition_승인_성공_흐름을_허용한다() {
        PaymentState approving = PaymentStateMachine.transition(
                PaymentState.REQUESTED,
                PaymentEvent.APPROVE_STARTED
        );
        PaymentState approved = PaymentStateMachine.transition(
                approving,
                PaymentEvent.APPROVE_SUCCEEDED
        );

        assertThat(approved).isEqualTo(PaymentState.APPROVED);
    }

    @Test
    void transition_timeout은_pending_confirmation으로_전이한다() {
        PaymentState approving = PaymentStateMachine.transition(
                PaymentState.REQUESTED,
                PaymentEvent.APPROVE_STARTED
        );
        PaymentState pending = PaymentStateMachine.transition(
                approving,
                PaymentEvent.APPROVE_TIMED_OUT
        );

        assertThat(pending).isEqualTo(PaymentState.PENDING_CONFIRMATION);
        assertThat(pending.requiresConfirmation()).isTrue();
    }

    @Test
    void transition_pending_confirmation은_confirm으로만_최종상태가_된다() {
        PaymentState pending = PaymentState.PENDING_CONFIRMATION;

        assertThat(PaymentStateMachine.transition(pending, PaymentEvent.CONFIRM_APPROVED))
                .isEqualTo(PaymentState.APPROVED);
        assertThat(PaymentStateMachine.transition(pending, PaymentEvent.CONFIRM_FAILED))
                .isEqualTo(PaymentState.FAILED);
    }

    @Test
    void transition_최종상태에서_다시_승인을_시작할_수_없다() {
        assertThatThrownBy(() -> PaymentStateMachine.transition(
                PaymentState.APPROVED,
                PaymentEvent.APPROVE_STARTED
        )).isInstanceOf(InvalidStateTransitionException.class);
    }
}