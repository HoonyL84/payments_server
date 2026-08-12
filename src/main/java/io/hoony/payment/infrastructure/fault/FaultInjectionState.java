package io.hoony.payment.infrastructure.fault;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("k6")
@Component
public class FaultInjectionState {
    private final AtomicReference<FailurePoint> armed = new AtomicReference<>();

    public void arm(FailurePoint point) {
        armed.set(point);
    }

    public boolean consume(FailurePoint point) {
        return armed.compareAndSet(point, null);
    }

    public void failFatallyIfArmed(FailurePoint point) {
        if (consume(point)) {
            throw new InjectedProcessInterruptionError(point);
        }
    }

    public void failIfArmed(FailurePoint point) {
        if (consume(point)) {
            throw new InjectedFailureException(point);
        }
    }
}
