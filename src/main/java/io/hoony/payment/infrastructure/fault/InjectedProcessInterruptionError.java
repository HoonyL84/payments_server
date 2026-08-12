package io.hoony.payment.infrastructure.fault;

public class InjectedProcessInterruptionError extends Error {
    public InjectedProcessInterruptionError(FailurePoint point) {
        super("Injected process interruption at " + point);
    }
}
