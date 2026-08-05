package io.hoony.payment.infrastructure.redis;

import io.hoony.payment.application.admission.IdempotencyAdmission;
import io.hoony.payment.application.port.out.IdempotencyAdmissionGate;
import io.hoony.payment.config.RedisIdempotencyGateProperties;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.idempotency.IdempotencyConflictException;
import io.hoony.payment.observability.PaymentMetrics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisIdempotencyAdmissionGate implements IdempotencyAdmissionGate {
    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyAdmissionGate.class);
    private static final String KEY_PREFIX = "payments:idempotency:";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redis;
    private final RedisIdempotencyGateProperties properties;
    private final PaymentMetrics metrics;

    public RedisIdempotencyAdmissionGate(
            StringRedisTemplate redis,
            RedisIdempotencyGateProperties properties,
            PaymentMetrics metrics
    ) {
        this.redis = redis;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public <T> T execute(IdempotencyAdmission admission, Supplier<T> action) {
        if (!properties.enabled()) {
            metrics.idempotencyGate(admission.operation(), "bypassed", Duration.ZERO);
            return action.get();
        }

        String redisKey = redisKey(admission);
        String value = UUID.randomUUID() + "|" + admission.fingerprint();
        long startedAt = System.nanoTime();
        Decision decision;
        try {
            decision = acquire(redisKey, value, admission.fingerprint());
        } catch (DataAccessException exception) {
            metrics.idempotencyGate(admission.operation(), "unavailable", elapsed(startedAt));
            log.warn("Redis idempotency gate unavailable. operation={}, ownerId={}",
                    admission.operation(), admission.ownerId());
            return action.get();
        }

        metrics.idempotencyGate(admission.operation(), decision.metricOutcome(), elapsed(startedAt));
        return switch (decision) {
            case ACQUIRED -> executeAndRelease(redisKey, value, admission, action);
            case DUPLICATE_IN_FLIGHT -> throw new ResourceConflictException(
                    "Idempotency request is already processing."
            );
            case PAYLOAD_CONFLICT -> throw new IdempotencyConflictException(admission.operation());
            case RACE_BYPASS -> action.get();
        };
    }

    private Decision acquire(String redisKey, String value, String fingerprint) {
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, value, properties.ttl()))) {
            return Decision.ACQUIRED;
        }

        String current = redis.opsForValue().get(redisKey);
        if (current == null) {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, value, properties.ttl()))) {
                return Decision.ACQUIRED;
            }
            current = redis.opsForValue().get(redisKey);
        }
        if (current == null) {
            return Decision.RACE_BYPASS;
        }

        int separator = current.indexOf('|');
        if (separator < 0) {
            return Decision.RACE_BYPASS;
        }
        return current.substring(separator + 1).equals(fingerprint)
                ? Decision.DUPLICATE_IN_FLIGHT
                : Decision.PAYLOAD_CONFLICT;
    }

    private <T> T executeAndRelease(
            String redisKey,
            String value,
            IdempotencyAdmission admission,
            Supplier<T> action
    ) {
        try {
            return action.get();
        } finally {
            try {
                redis.execute(RELEASE_SCRIPT, List.of(redisKey), value);
            } catch (DataAccessException exception) {
                metrics.idempotencyGate(admission.operation(), "release_failed", Duration.ZERO);
                log.warn("Redis idempotency gate release failed. operation={}, ownerId={}",
                        admission.operation(), admission.ownerId());
            }
        }
    }

    private String redisKey(IdempotencyAdmission admission) {
        String source = admission.operation() + "\u0000"
                + admission.ownerId() + "\u0000"
                + admission.idempotencyKey();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    private enum Decision {
        ACQUIRED("acquired"),
        DUPLICATE_IN_FLIGHT("rejected"),
        PAYLOAD_CONFLICT("conflict"),
        RACE_BYPASS("race_bypass");

        private final String metricOutcome;

        Decision(String metricOutcome) {
            this.metricOutcome = metricOutcome;
        }

        private String metricOutcome() {
            return metricOutcome;
        }
    }
}
