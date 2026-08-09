package io.hoony.payment.observability;

import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class PaymentMetrics {
    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void result(String flow, String outcome) {
        counter("payments.flow.results", "flow", flow, "outcome", outcome).increment();
    }

    public void idempotency(String operation, String outcome) {
        counter("payments.idempotency.requests", "operation", operation, "outcome", outcome).increment();
    }

    public void invalidTransition() {
        counter("payments.state.invalid.transitions").increment();
    }

    public void idempotencyGate(IdempotencyOperation operation, String outcome, Duration duration) {
        String operationTag = operation.name().toLowerCase();
        counter("payments.idempotency.gate", "operation", operationTag, "outcome", outcome).increment();
        timer("payments.idempotency.gate.duration", "operation", operationTag, "outcome", outcome)
                .record(duration);
    }

    public <T> T time(String metric, String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = action.get();
            sample.stop(timer(metric, "operation", operation, "outcome", "success"));
            return result;
        } catch (RuntimeException exception) {
            sample.stop(timer(metric, "operation", operation, "outcome", "error"));
            throw exception;
        }
    }

    public void outboxPublished(Instant createdAt) {
        counter("payments.outbox.publish", "outcome", "success").increment();
        timer("payments.outbox.publish.lag").record(Duration.between(createdAt, Instant.now()));
    }

    public void outboxFailed() {
        counter("payments.outbox.publish", "outcome", "failed").increment();
    }

    public void outboxClaimed(int count) {
        if (count > 0) {
            counter("payments.outbox.claimed").increment(count);
        }
    }

    public void outboxClaimLost(String phase) {
        counter("payments.outbox.claim.lost", "phase", phase).increment();
    }

    public void providerAdmission(String operation, String outcome) {
        counter("payments.provider.admission", "operation", operation, "outcome", outcome).increment();
    }

    public void providerCall(String operation, String outcome) {
        counter("payments.provider.calls", "operation", operation, "outcome", outcome).increment();
    }

    public void providerRetry(String operation, String outcome) {
        counter("payments.provider.retries", "operation", operation, "outcome", outcome).increment();
    }

    public void recovery(String action, String outcome, Duration lag) {
        counter("payments.recovery.results", "action", action, "outcome", outcome).increment();
        timer("payments.reconcile.lag", "action", action).record(lag);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).publishPercentileHistogram().register(registry);
    }
}
