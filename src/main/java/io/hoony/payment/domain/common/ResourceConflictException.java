package io.hoony.payment.domain.common;

public class ResourceConflictException extends DomainException {

    public ResourceConflictException(String message) {
        super(message);
    }
}
