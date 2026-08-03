package io.hoony.payment.infrastructure.fault;

public class InjectedFailureException extends RuntimeException {
    public InjectedFailureException(FailurePoint point) {
        super("Injected failure at " + point);
    }
}
