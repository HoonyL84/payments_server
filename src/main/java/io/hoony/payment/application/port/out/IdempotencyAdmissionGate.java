package io.hoony.payment.application.port.out;

import io.hoony.payment.application.admission.IdempotencyAdmission;
import java.util.function.Supplier;

public interface IdempotencyAdmissionGate {
    <T> T execute(IdempotencyAdmission admission, Supplier<T> action);

    static IdempotencyAdmissionGate bypassing() {
        return new IdempotencyAdmissionGate() {
            @Override
            public <T> T execute(IdempotencyAdmission admission, Supplier<T> action) {
                return action.get();
            }
        };
    }
}
