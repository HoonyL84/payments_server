package io.hoony.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payments.outbox.relay")
public record OutboxRelayProperties(Duration leaseDuration) {
    public OutboxRelayProperties {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Outbox relay lease duration must be positive.");
        }
    }
}
