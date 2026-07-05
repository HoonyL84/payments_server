package io.hoony.payment.domain.payment;

import io.hoony.payment.domain.common.DomainException;

/**
 * Raised when a payment lifecycle receives an invalid state transition.
 */
public class InvalidStateTransitionException extends DomainException {

    /**
     * Creates an invalid transition exception.
     */
    public InvalidStateTransitionException(String message) {
        super(message);
    }
}