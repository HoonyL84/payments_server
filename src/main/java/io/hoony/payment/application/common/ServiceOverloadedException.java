package io.hoony.payment.application.common;

public class ServiceOverloadedException extends RuntimeException {
    public ServiceOverloadedException(String message) {
        super(message);
    }
}
