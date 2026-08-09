package io.hoony.payment.application.admission;

import io.hoony.payment.application.common.ServiceOverloadedException;
import io.hoony.payment.config.ProviderProtectionProperties;
import io.hoony.payment.infrastructure.pg.ProviderOperation;
import io.hoony.payment.observability.PaymentMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ProviderCapacityGuard {
    private final Semaphore commandPermits;
    private final Semaphore inquiryPermits;
    private final AtomicInteger commandInFlight = new AtomicInteger();
    private final AtomicInteger inquiryInFlight = new AtomicInteger();
    private final PaymentMetrics metrics;

    public ProviderCapacityGuard(
            ProviderProtectionProperties properties,
            PaymentMetrics metrics,
            MeterRegistry registry
    ) {
        commandPermits = new Semaphore(properties.commandMaxConcurrent());
        inquiryPermits = new Semaphore(properties.inquiryAdmissionCapacity());
        this.metrics = metrics;
        Gauge.builder("payments.provider.admission.inflight", commandInFlight, AtomicInteger::get)
                .tag("workload", "command")
                .register(registry);
        Gauge.builder("payments.provider.admission.inflight", inquiryInFlight, AtomicInteger::get)
                .tag("workload", "inquiry")
                .register(registry);
    }

    public <T> T execute(ProviderOperation operation, Supplier<T> action) {
        Semaphore permits = operation.isInquiry() ? inquiryPermits : commandPermits;
        AtomicInteger inFlight = operation.isInquiry() ? inquiryInFlight : commandInFlight;
        String workload = operation.isInquiry() ? "inquiry" : "command";
        if (!permits.tryAcquire()) {
            metrics.providerAdmission(operation.metricTag(), "rejected");
            throw new ServiceOverloadedException(
                    "Provider " + workload + " capacity is exhausted. Retry later."
            );
        }
        inFlight.incrementAndGet();
        metrics.providerAdmission(operation.metricTag(), "accepted");
        try {
            return action.get();
        } finally {
            inFlight.decrementAndGet();
            permits.release();
        }
    }

    public int commandInFlight() {
        return commandInFlight.get();
    }

    public int inquiryInFlight() {
        return inquiryInFlight.get();
    }
}
