package io.hoony.payment.infrastructure.pg;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.hoony.payment.application.common.ProviderUnavailableException;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.config.ProviderProtectionProperties;
import io.hoony.payment.infrastructure.fault.InjectedFailureException;
import io.hoony.payment.observability.PaymentMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!test & !integration")
@Component
public class ProviderCallExecutor {
    private final ProviderProtectionProperties properties;
    private final PaymentMetrics metrics;
    private final ThreadPoolExecutor commandExecutor;
    private final ThreadPoolExecutor inquiryExecutor;
    private final Semaphore retryPermits;
    private final AtomicInteger retryInFlight = new AtomicInteger();
    private final AtomicInteger maxRetryInFlight = new AtomicInteger();
    private final AtomicInteger maxInquiryQueueDepth = new AtomicInteger();
    private final Map<ProviderOperation, CircuitBreaker> breakers = new EnumMap<>(ProviderOperation.class);

    public ProviderCallExecutor(
            ProviderProtectionProperties properties,
            PaymentMetrics metrics,
            MeterRegistry registry
    ) {
        this.properties = properties;
        this.metrics = metrics;
        commandExecutor = executor(
                "provider-command-",
                properties.commandMaxConcurrent(),
                new SynchronousQueue<>()
        );
        inquiryExecutor = executor(
                "provider-inquiry-",
                properties.inquiryMaxConcurrent(),
                inquiryQueue(properties.inquiryQueueCapacity())
        );
        retryPermits = new Semaphore(properties.maxConcurrentRetries());
        CircuitBreakerConfig circuitConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.circuitSlidingWindowSize())
                .minimumNumberOfCalls(properties.circuitMinimumCalls())
                .failureRateThreshold(properties.circuitFailureRateThreshold())
                .waitDurationInOpenState(properties.circuitOpenDuration())
                .permittedNumberOfCallsInHalfOpenState(properties.circuitHalfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordResult(ProviderCallExecutor::isUnknownResult)
                .build();
        for (ProviderOperation operation : ProviderOperation.values()) {
            CircuitBreaker breaker = CircuitBreaker.of(operation.metricTag(), circuitConfig);
            breakers.put(operation, breaker);
            Gauge.builder("payments.provider.circuit.state", breaker, ProviderCallExecutor::stateValue)
                    .tag("operation", operation.metricTag())
                    .register(registry);
        }
        registerExecutorGauges(registry, commandExecutor, "command");
        registerExecutorGauges(registry, inquiryExecutor, "inquiry");
        Gauge.builder("payments.provider.retry.inflight", retryInFlight, AtomicInteger::get)
                .register(registry);
    }

    public void ensureAvailable(ProviderOperation operation) {
        CircuitBreaker.State state = breaker(operation).getState();
        if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN) {
            metrics.providerCall(operation.metricTag(), "circuit_rejected");
            throw new ProviderUnavailableException(
                    "Provider circuit is open for " + operation.metricTag() + "."
            );
        }
    }

    public <T> T executeCommand(
            ProviderOperation operation,
            Supplier<T> action,
            Supplier<T> unknownFallback
    ) {
        try {
            T result = executeAttempt(
                    operation,
                    commandExecutor,
                    properties.commandTimeout(),
                    action
            );
            metrics.providerCall(operation.metricTag(), isUnknownResult(result) ? "unknown" : "success");
            return result;
        } catch (InjectedFailureException exception) {
            throw exception;
        } catch (ProviderCallInterruptedException exception) {
            metrics.providerCall(operation.metricTag(), "interrupted");
        } catch (CallNotPermittedException exception) {
            metrics.providerCall(operation.metricTag(), "circuit_rejected");
        } catch (ProviderCallTimeoutException exception) {
            metrics.providerCall(operation.metricTag(), "timeout");
        } catch (RejectedExecutionException exception) {
            metrics.providerCall(operation.metricTag(), "bulkhead_rejected");
        } catch (RuntimeException exception) {
            metrics.providerCall(operation.metricTag(), "error");
        }
        return unknownFallback.get();
    }

    public <T> T executeInquiry(
            ProviderOperation operation,
            Supplier<T> action,
            Predicate<T> retryableResult,
            Supplier<T> unknownFallback
    ) {
        long deadline = System.nanoTime() + properties.inquiryRequestBudget().toNanos();
        T lastResult = null;
        for (int attempt = 1; attempt <= properties.inquiryMaxAttempts(); attempt++) {
            boolean retryPermit = false;
            try {
                if (attempt > 1) {
                    retryPermit = acquireRetryPermit(operation);
                    if (!retryPermit) {
                        return unknownFallback.get();
                    }
                    if (!waitForRetry(operation, attempt, deadline)) {
                        return unknownFallback.get();
                    }
                }
                Duration timeout = remainingTimeout(deadline);
                lastResult = executeAttempt(operation, inquiryExecutor, timeout, action);
                if (!retryableResult.test(lastResult)) {
                    metrics.providerCall(operation.metricTag(), "success");
                    return lastResult;
                }
                metrics.providerCall(operation.metricTag(), "unknown");
            } catch (InjectedFailureException exception) {
                throw exception;
            } catch (ProviderCallInterruptedException exception) {
                metrics.providerCall(operation.metricTag(), "interrupted");
                return unknownFallback.get();
            } catch (CallNotPermittedException exception) {
                metrics.providerCall(operation.metricTag(), "circuit_rejected");
                return unknownFallback.get();
            } catch (ProviderCallTimeoutException exception) {
                metrics.providerCall(operation.metricTag(), "timeout");
            } catch (RejectedExecutionException exception) {
                metrics.providerCall(operation.metricTag(), "bulkhead_rejected");
                return unknownFallback.get();
            } catch (RetryBudgetExhaustedException exception) {
                metrics.providerRetry(operation.metricTag(), "budget_exhausted");
                return unknownFallback.get();
            } catch (RuntimeException exception) {
                metrics.providerCall(operation.metricTag(), "error");
            } finally {
                if (retryPermit) {
                    retryInFlight.decrementAndGet();
                    retryPermits.release();
                }
            }
        }
        return lastResult == null ? unknownFallback.get() : lastResult;
    }

    public String circuitState(ProviderOperation operation) {
        return breaker(operation).getState().name();
    }

    public int commandActive() {
        return commandExecutor.getActiveCount();
    }

    public int inquiryActive() {
        return inquiryExecutor.getActiveCount();
    }

    public int inquiryQueued() {
        return inquiryExecutor.getQueue().size();
    }

    public int maxInquiryQueueDepth() {
        return maxInquiryQueueDepth.get();
    }

    public int retryInFlight() {
        return retryInFlight.get();
    }

    public int maxRetryInFlight() {
        return maxRetryInFlight.get();
    }

    public void reset() {
        breakers.values().forEach(CircuitBreaker::reset);
        maxInquiryQueueDepth.set(0);
        maxRetryInFlight.set(retryInFlight.get());
    }

    @PreDestroy
    public void close() {
        commandExecutor.shutdownNow();
        inquiryExecutor.shutdownNow();
    }

    private <T> T executeAttempt(
            ProviderOperation operation,
            ThreadPoolExecutor executor,
            Duration timeout,
            Supplier<T> action
    ) {
        return breaker(operation).executeSupplier(() -> waitFor(executor, timeout, action));
    }

    private <T> T waitFor(ThreadPoolExecutor executor, Duration timeout, Supplier<T> action) {
        Future<T> future = executor.submit(action::get);
        if (executor == inquiryExecutor) {
            maxInquiryQueueDepth.accumulateAndGet(executor.getQueue().size(), Math::max);
        }
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ProviderCallTimeoutException(exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderCallInterruptedException(exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Provider call failed.", exception.getCause());
        }
    }

    private boolean acquireRetryPermit(ProviderOperation operation) {
        if (!retryPermits.tryAcquire()) {
            metrics.providerRetry(operation.metricTag(), "suppressed");
            return false;
        }
        int current = retryInFlight.incrementAndGet();
        maxRetryInFlight.accumulateAndGet(current, Math::max);
        metrics.providerRetry(operation.metricTag(), "attempted");
        return true;
    }

    private boolean waitForRetry(ProviderOperation operation, int attempt, long deadline) {
        long remaining = deadline - System.nanoTime();
        long cap = backoffCapNanos(attempt);
        if (remaining <= cap) {
            metrics.providerRetry(operation.metricTag(), "budget_exhausted");
            return false;
        }
        long delay = cap == 0 ? 0 : ThreadLocalRandom.current().nextLong(cap + 1);
        try {
            TimeUnit.NANOSECONDS.sleep(delay);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            metrics.providerRetry(operation.metricTag(), "interrupted");
            return false;
        }
    }

    private Duration remainingTimeout(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new RetryBudgetExhaustedException();
        }
        return Duration.ofNanos(Math.min(properties.inquiryTimeout().toNanos(), remaining));
    }

    private long backoffCapNanos(int attempt) {
        double multiplier = Math.pow(2, attempt - 2);
        double nanos = properties.retryInitialBackoff().toNanos() * multiplier;
        return (long) Math.min(nanos, properties.inquiryRequestBudget().toNanos());
    }

    private CircuitBreaker breaker(ProviderOperation operation) {
        return breakers.get(operation);
    }

    private static ThreadPoolExecutor executor(
            String threadPrefix,
            int size,
            BlockingQueue<Runnable> queue
    ) {
        return new ThreadPoolExecutor(
                size,
                size,
                0,
                TimeUnit.MILLISECONDS,
                queue,
                daemonThreadFactory(threadPrefix),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static BlockingQueue<Runnable> inquiryQueue(int capacity) {
        return capacity == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(capacity);
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static boolean isUnknownResult(Object result) {
        return (result instanceof PaymentGateway.ApprovalResult approval
                && approval.status() == PaymentGateway.ApprovalStatus.TIMED_OUT)
                || (result instanceof PaymentGateway.ConfirmationResult confirmation
                && confirmation.status() == PaymentGateway.ConfirmationStatus.UNKNOWN)
                || (result instanceof PaymentGateway.CancellationResult cancellation
                && cancellation.status() == PaymentGateway.CancellationStatus.TIMED_OUT)
                || (result instanceof PaymentGateway.CancellationConfirmationResult confirmation
                && confirmation.status() == PaymentGateway.CancellationConfirmationStatus.UNKNOWN);
    }

    private static double stateValue(CircuitBreaker breaker) {
        return switch (breaker.getState()) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN, FORCED_OPEN -> 2;
            case DISABLED, METRICS_ONLY -> -1;
        };
    }

    private static void registerExecutorGauges(
            MeterRegistry registry,
            ThreadPoolExecutor executor,
            String workload
    ) {
        Gauge.builder("payments.provider.executor.active", executor, ThreadPoolExecutor::getActiveCount)
                .tag("workload", workload)
                .register(registry);
        Gauge.builder("payments.provider.executor.queued", executor, value -> value.getQueue().size())
                .tag("workload", workload)
                .register(registry);
    }

    private static final class ProviderCallTimeoutException extends RuntimeException {
        private ProviderCallTimeoutException(Throwable cause) {
            super(cause);
        }
    }

    private static final class ProviderCallInterruptedException extends RuntimeException {
        private ProviderCallInterruptedException(Throwable cause) {
            super(cause);
        }
    }

    private static final class RetryBudgetExhaustedException extends RuntimeException {
    }
}
