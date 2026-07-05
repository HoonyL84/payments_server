package io.hoony.payment.domain.common;

/**
 * Domain rule violation base exception.
 */
public class DomainException extends RuntimeException {

    /**
     * Creates a domain exception with a stable message.
     */
    public DomainException(String message) {
        super(message);
    }
}