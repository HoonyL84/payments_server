package io.hoony.payment.application.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hoony.payment.application.common.ServiceOverloadedException;
import io.hoony.payment.config.ProviderProtectionProperties;
import io.hoony.payment.infrastructure.pg.ProviderOperation;
import io.hoony.payment.observability.PaymentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProviderCapacityGuardTest {
    @Test
    void rejectsCommandImmediatelyWhenCapacityIsExhausted() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProviderCapacityGuard guard = new ProviderCapacityGuard(
                properties(),
                new PaymentMetrics(registry),
                registry
        );
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> guard.execute(ProviderOperation.APPROVE, () -> {
                entered.countDown();
                await(release);
                return "accepted";
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> guard.execute(ProviderOperation.APPROVE, () -> "rejected"))
                    .isInstanceOf(ServiceOverloadedException.class);
            assertThat(guard.commandInFlight()).isEqualTo(1);

            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("accepted");
            assertThat(guard.commandInFlight()).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private ProviderProtectionProperties properties() {
        return new ProviderProtectionProperties(
                1,
                1,
                1,
                1,
                Duration.ofSeconds(1),
                Duration.ofMillis(200),
                Duration.ofSeconds(1),
                2,
                Duration.ofMillis(10),
                10,
                5,
                50,
                Duration.ofSeconds(5),
                1
        );
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
