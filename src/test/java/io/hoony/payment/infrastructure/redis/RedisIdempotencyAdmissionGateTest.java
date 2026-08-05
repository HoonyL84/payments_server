package io.hoony.payment.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hoony.payment.application.admission.IdempotencyAdmission;
import io.hoony.payment.config.RedisIdempotencyGateProperties;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.idempotency.IdempotencyConflictException;
import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.hoony.payment.observability.PaymentMetrics;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

@SuppressWarnings("unchecked")
class RedisIdempotencyAdmissionGateTest {
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String FINGERPRINT = "fingerprint-a";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final PaymentMetrics metrics = mock(PaymentMetrics.class);

    @Test
    void acquiredGateExecutesActionOnceAndReleasesOwnedValue() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), eq(TTL))).thenReturn(true);
        RedisIdempotencyAdmissionGate gate = gate(true);

        String result = gate.execute(admission(FINGERPRINT), () -> "approved");

        assertThat(result).isEqualTo("approved");
        verify(redis).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void sameFingerprintInFlightIsRejectedBeforeAction() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), eq(TTL))).thenReturn(false);
        when(values.get(anyString())).thenReturn("owner-token|" + FINGERPRINT);
        RedisIdempotencyAdmissionGate gate = gate(true);
        Supplier<String> action = mock(Supplier.class);

        assertThatThrownBy(() -> gate.execute(admission(FINGERPRINT), action))
                .isInstanceOf(ResourceConflictException.class);
        verify(action, never()).get();
    }

    @Test
    void differentFingerprintInFlightIsRejectedAsConflict() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), eq(TTL))).thenReturn(false);
        when(values.get(anyString())).thenReturn("owner-token|fingerprint-b");
        RedisIdempotencyAdmissionGate gate = gate(true);
        Supplier<String> action = mock(Supplier.class);

        assertThatThrownBy(() -> gate.execute(admission(FINGERPRINT), action))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(action, never()).get();
    }

    @Test
    void redisFailureFallsBackToDatabasePathWithoutRetryingAction() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        RedisIdempotencyAdmissionGate gate = gate(true);
        Supplier<String> action = mock(Supplier.class);
        when(action.get()).thenReturn("database-result");

        String result = gate.execute(admission(FINGERPRINT), action);

        assertThat(result).isEqualTo("database-result");
        verify(action).get();
    }

    @Test
    void disabledGateBypassesRedis() {
        RedisIdempotencyAdmissionGate gate = gate(false);

        assertThat(gate.execute(admission(FINGERPRINT), () -> "database-result"))
                .isEqualTo("database-result");
        verify(redis, never()).opsForValue();
    }

    private RedisIdempotencyAdmissionGate gate(boolean enabled) {
        return new RedisIdempotencyAdmissionGate(
                redis,
                new RedisIdempotencyGateProperties(enabled, TTL),
                metrics
        );
    }

    private IdempotencyAdmission admission(String fingerprint) {
        return new IdempotencyAdmission(
                IdempotencyOperation.APPROVE,
                "merchant-1",
                "idempotency-key",
                fingerprint
        );
    }
}
