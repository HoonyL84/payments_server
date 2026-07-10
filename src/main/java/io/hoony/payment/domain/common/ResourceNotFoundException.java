package io.hoony.payment.domain.common;

public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
