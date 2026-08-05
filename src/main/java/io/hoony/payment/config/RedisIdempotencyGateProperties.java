package io.hoony.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payments.idempotency.redis-gate")
public record RedisIdempotencyGateProperties(boolean enabled, Duration ttl) {
    public RedisIdempotencyGateProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Redis idempotency gate TTL must be positive.");
        }
    }
}
