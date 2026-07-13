package io.hoony.payment.domain.cancellation;

import io.hoony.payment.domain.payment.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationStateMachineTest {

    @Test
    void transition_취소_성공_흐름을_허용한다() {
        CancellationState canceling = CancellationStateMachine.transition(
                CancellationState.CANCEL_REQUESTED,
                CancellationEvent.CANCEL_STARTED
        );
        CancellationState canceled = CancellationStateMachine.transition(
                canceling,
                CancellationEvent.CANCEL_SUCCEEDED
        );

        assertThat(canceled).isEqualTo(CancellationState.CANCELED);
    }

    @Test
    void transition_취소_timeout은_pending_confirmation으로_전이한다() {
        CancellationState canceling = CancellationStateMachine.transition(
                CancellationState.CANCEL_REQUESTED,
                CancellationEvent.CANCEL_STARTED
        );
        CancellationState pending = CancellationStateMachine.transition(
                canceling,
                CancellationEvent.CANCEL_TIMED_OUT
        );

        assertThat(pending).isEqualTo(CancellationState.CANCEL_PENDING_CONFIRMATION);
        assertThat(pending.requiresConfirmation()).isTrue();
    }

    @Test
    void transition_confirm은_선점상태를거쳐_pending으로돌아갈수있다() {
        CancellationState confirming = CancellationStateMachine.transition(
                CancellationState.CANCEL_PENDING_CONFIRMATION,
                CancellationEvent.CONFIRM_CANCEL_STARTED
        );
        CancellationState pending = CancellationStateMachine.transition(
                confirming,
                CancellationEvent.CONFIRM_CANCEL_UNKNOWN
        );

        assertThat(confirming).isEqualTo(CancellationState.CANCEL_CONFIRMING);
        assertThat(pending).isEqualTo(CancellationState.CANCEL_PENDING_CONFIRMATION);
    }
    @Test
    void transition_취소_최종상태에서_다시_시작할_수_없다() {
        assertThatThrownBy(() -> CancellationStateMachine.transition(
                CancellationState.CANCELED,
                CancellationEvent.CANCEL_STARTED
        )).isInstanceOf(InvalidStateTransitionException.class);
    }
}