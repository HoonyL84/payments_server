package io.hoony.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hoony.payment.application.common.ProviderUnavailableException;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.config.ProviderProtectionProperties;
import io.hoony.payment.infrastructure.fault.FailurePoint;
import io.hoony.payment.infrastructure.fault.InjectedFailureException;
import io.hoony.payment.observability.PaymentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderCallExecutorTest {
    private ProviderCallExecutor executor;

    @AfterEach
    void closeExecutor() {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    void commandTimeoutDoesNotRetryApproval() {
        executor = executor(properties(1, 4, 10));
        AtomicInteger calls = new AtomicInteger();

        PaymentGateway.ApprovalResult result = executor.executeCommand(
                ProviderOperation.APPROVE,
                () -> {
                    calls.incrementAndGet();
                    sleep(Duration.ofMillis(100));
                    return PaymentGateway.ApprovalResult.approved("late-approval");
                },
                PaymentGateway.ApprovalResult::timedOut
        );

        assertThat(result.status()).isEqualTo(PaymentGateway.ApprovalStatus.TIMED_OUT);
        assertThat(calls).hasValue(1);
    }

    @Test
    void inquiryStopsAtPerRequestAttemptLimit() {
        executor = executor(properties(2, 3, 10));
        AtomicInteger calls = new AtomicInteger();

        PaymentGateway.ConfirmationResult result = executor.executeInquiry(
                ProviderOperation.CONFIRM_APPROVE,
                () -> {
                    calls.incrementAndGet();
                    return PaymentGateway.ConfirmationResult.unknown();
                },
                value -> value.status() == PaymentGateway.ConfirmationStatus.UNKNOWN,
                PaymentGateway.ConfirmationResult::unknown
        );

        assertThat(result.status()).isEqualTo(PaymentGateway.ConfirmationStatus.UNKNOWN);
        assertThat(calls).hasValue(3);
    }

    @Test
    void concurrentRetryPermitSuppressesAdditionalRetry() throws Exception {
        executor = executor(properties(1, 3, 10));
        CountDownLatch firstRetryEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRetry = new CountDownLatch(1);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        var callers = Executors.newSingleThreadExecutor();
        try {
            var first = callers.submit(() -> executor.executeInquiry(
                    ProviderOperation.CONFIRM_APPROVE,
                    () -> {
                        int current = firstCalls.incrementAndGet();
                        if (current == 2) {
                            firstRetryEntered.countDown();
                            await(releaseFirstRetry);
                            return PaymentGateway.ConfirmationResult.approved("confirmed");
                        }
                        return PaymentGateway.ConfirmationResult.unknown();
                    },
                    value -> value.status() == PaymentGateway.ConfirmationStatus.UNKNOWN,
                    PaymentGateway.ConfirmationResult::unknown
            ));
            assertThat(firstRetryEntered.await(2, TimeUnit.SECONDS)).isTrue();

            PaymentGateway.ConfirmationResult second = executor.executeInquiry(
                    ProviderOperation.CONFIRM_APPROVE,
                    () -> {
                        secondCalls.incrementAndGet();
                        return PaymentGateway.ConfirmationResult.unknown();
                    },
                    value -> value.status() == PaymentGateway.ConfirmationStatus.UNKNOWN,
                    PaymentGateway.ConfirmationResult::unknown
            );

            assertThat(second.status()).isEqualTo(PaymentGateway.ConfirmationStatus.UNKNOWN);
            assertThat(secondCalls).hasValue(1);
            assertThat(executor.maxRetryInFlight()).isEqualTo(1);
            releaseFirstRetry.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).status())
                    .isEqualTo(PaymentGateway.ConfirmationStatus.APPROVED);
        } finally {
            releaseFirstRetry.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void processInterruptionSimulationIsNotConvertedToUnknown() {
        executor = executor(properties(1, 1, 10));

        assertThatThrownBy(() -> executor.executeCommand(
                ProviderOperation.APPROVE,
                () -> {
                    throw new InjectedFailureException(FailurePoint.BEFORE_PG);
                },
                PaymentGateway.ApprovalResult::timedOut
        )).isInstanceOf(InjectedFailureException.class);
    }

    @Test
    void circuitOpensAfterConfiguredUnknownResults() {
        executor = executor(properties(1, 1, 2));

        for (int index = 0; index < 2; index++) {
            executor.executeCommand(
                    ProviderOperation.APPROVE,
                    PaymentGateway.ApprovalResult::timedOut,
                    PaymentGateway.ApprovalResult::timedOut
            );
        }

        assertThat(executor.circuitState(ProviderOperation.APPROVE)).isEqualTo("OPEN");
        assertThatThrownBy(() -> executor.ensureAvailable(ProviderOperation.APPROVE))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    private ProviderCallExecutor executor(ProviderProtectionProperties properties) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return new ProviderCallExecutor(properties, new PaymentMetrics(registry), registry);
    }

    private ProviderProtectionProperties properties(
            int maxConcurrentRetries,
            int inquiryMaxAttempts,
            int circuitMinimumCalls
    ) {
        return new ProviderProtectionProperties(
                1,
                2,
                2,
                maxConcurrentRetries,
                Duration.ofMillis(20),
                Duration.ofMillis(100),
                Duration.ofSeconds(2),
                inquiryMaxAttempts,
                Duration.ofMillis(1),
                Math.max(10, circuitMinimumCalls),
                circuitMinimumCalls,
                50,
                Duration.ofSeconds(5),
                1
        );
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test wait interrupted.", exception);
        }
    }
}
