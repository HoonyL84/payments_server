package io.hoony.mockpg;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class MockPgBehavior {

    private final AtomicReference<Mode> approve = new AtomicReference<>(Mode.APPROVED);
    private final AtomicReference<Mode> cancel = new AtomicReference<>(Mode.CANCELED);
    private volatile Duration responseDelay = Duration.ZERO;
    private volatile Duration webhookDelay = Duration.ofSeconds(1);
    private volatile boolean webhookEnabled;

    public Snapshot snapshot(Operation operation) {
        return new Snapshot(
                operation == Operation.APPROVE ? approve.get() : cancel.get(),
                responseDelay,
                webhookDelay,
                webhookEnabled
        );
    }

    public void configure(Configuration configuration) {
        if (configuration.approve() != null) {
            approve.set(configuration.approve());
        }
        if (configuration.cancel() != null) {
            cancel.set(configuration.cancel());
        }
        if (configuration.responseDelayMillis() != null) {
            responseDelay = nonNegative(configuration.responseDelayMillis());
        }
        if (configuration.webhookDelayMillis() != null) {
            webhookDelay = nonNegative(configuration.webhookDelayMillis());
        }
        if (configuration.webhookEnabled() != null) {
            webhookEnabled = configuration.webhookEnabled();
        }
    }

    public void reset() {
        approve.set(Mode.APPROVED);
        cancel.set(Mode.CANCELED);
        responseDelay = Duration.ZERO;
        webhookDelay = Duration.ofSeconds(1);
        webhookEnabled = false;
    }

    private static Duration nonNegative(long millis) {
        if (millis < 0 || millis > 10_000) {
            throw new IllegalArgumentException("Delay must be between 0 and 10000 ms.");
        }
        return Duration.ofMillis(millis);
    }

    public record Configuration(
            Mode approve,
            Mode cancel,
            Long responseDelayMillis,
            Long webhookDelayMillis,
            Boolean webhookEnabled
    ) {
    }

    public record Snapshot(Mode mode, Duration responseDelay, Duration webhookDelay, boolean webhookEnabled) {
    }

    public enum Operation {
        APPROVE,
        CANCEL
    }

    public enum Mode {
        APPROVED,
        CANCELED,
        DECLINED,
        APPROVED_RESPONSE_LOST,
        CANCELED_RESPONSE_LOST,
        CONNECTION_FAILURE
    }
}