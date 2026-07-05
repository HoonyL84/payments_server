package io.hoony.payment.domain.cancellation;

import io.hoony.payment.domain.payment.InvalidStateTransitionException;

/**
 * Directed state graph for payment cancellation.
 */
public final class CancellationStateMachine {

    private CancellationStateMachine() {
    }

    /**
     * Applies a cancellation event to the current state.
     */
    public static CancellationState transition(CancellationState current, CancellationEvent event) {
        return switch (event) {
            case CANCEL_STARTED -> require(
                    current,
                    CancellationState.CANCEL_REQUESTED,
                    event,
                    CancellationState.CANCELING
            );
            case CANCEL_SUCCEEDED -> require(
                    current,
                    CancellationState.CANCELING,
                    event,
                    CancellationState.CANCELED
            );
            case CANCEL_FAILED -> requireAny(
                    current,
                    event,
                    CancellationState.CANCEL_FAILED,
                    CancellationState.CANCEL_REQUESTED,
                    CancellationState.CANCELING
            );
            case CANCEL_TIMED_OUT -> require(
                    current,
                    CancellationState.CANCELING,
                    event,
                    CancellationState.CANCEL_PENDING_CONFIRMATION
            );
            case CONFIRM_CANCELED -> require(
                    current,
                    CancellationState.CANCEL_PENDING_CONFIRMATION,
                    event,
                    CancellationState.CANCELED
            );
            case CONFIRM_CANCEL_FAILED -> require(
                    current,
                    CancellationState.CANCEL_PENDING_CONFIRMATION,
                    event,
                    CancellationState.CANCEL_FAILED
            );
        };
    }

    private static CancellationState require(
            CancellationState current,
            CancellationState expected,
            CancellationEvent event,
            CancellationState next
    ) {
        if (current != expected) {
            throw invalid(current, event);
        }
        return next;
    }

    private static CancellationState requireAny(
            CancellationState current,
            CancellationEvent event,
            CancellationState next,
            CancellationState... allowed
    ) {
        for (CancellationState state : allowed) {
            if (current == state) {
                return next;
            }
        }
        throw invalid(current, event);
    }

    private static InvalidStateTransitionException invalid(CancellationState current, CancellationEvent event) {
        return new InvalidStateTransitionException(
                "Invalid cancellation transition. current=" + current + ", event=" + event
        );
    }
}