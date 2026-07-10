package io.hoony.payment.domain.payment;

public final class PaymentStateMachine {

    private PaymentStateMachine() {
    }

    public static PaymentState transition(PaymentState current, PaymentEvent event) {
        return switch (event) {
            case APPROVE_STARTED -> require(current, PaymentState.REQUESTED, event, PaymentState.APPROVING);
            case APPROVE_SUCCEEDED -> require(current, PaymentState.APPROVING, event, PaymentState.APPROVED);
            case APPROVE_FAILED -> requireAny(
                    current,
                    event,
                    PaymentState.FAILED,
                    PaymentState.REQUESTED,
                    PaymentState.APPROVING
            );
            case APPROVE_TIMED_OUT -> require(
                    current,
                    PaymentState.APPROVING,
                    event,
                    PaymentState.PENDING_CONFIRMATION
            );
            case CONFIRM_STARTED -> require(
                    current,
                    PaymentState.PENDING_CONFIRMATION,
                    event,
                    PaymentState.CONFIRMING
            );
            case CONFIRM_APPROVED -> require(
                    current,
                    PaymentState.CONFIRMING,
                    event,
                    PaymentState.APPROVED
            );
            case CONFIRM_FAILED -> require(
                    current,
                    PaymentState.CONFIRMING,
                    event,
                    PaymentState.FAILED
            );
            case CONFIRM_UNKNOWN -> require(
                    current,
                    PaymentState.CONFIRMING,
                    event,
                    PaymentState.PENDING_CONFIRMATION
            );
        };
    }

    private static PaymentState require(
            PaymentState current,
            PaymentState expected,
            PaymentEvent event,
            PaymentState next
    ) {
        if (current != expected) {
            throw invalid(current, event);
        }
        return next;
    }

    private static PaymentState requireAny(
            PaymentState current,
            PaymentEvent event,
            PaymentState next,
            PaymentState... allowed
    ) {
        for (PaymentState state : allowed) {
            if (current == state) {
                return next;
            }
        }
        throw invalid(current, event);
    }

    private static InvalidStateTransitionException invalid(PaymentState current, PaymentEvent event) {
        return new InvalidStateTransitionException(
                "Invalid payment transition. current=" + current + ", event=" + event
        );
    }
}
