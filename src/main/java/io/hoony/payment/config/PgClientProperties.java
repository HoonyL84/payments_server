package io.hoony.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "payments.pg")
public record PgClientProperties(
        String mode,
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        String webhookSecret,
        Duration webhookMaxAge
) {
    public PgClientProperties {
        mode = mode == null || mode.isBlank() ? "fake" : mode;
        baseUrl = baseUrl == null ? URI.create("http://localhost:8090") : baseUrl;
        connectTimeout = positiveOrDefault(connectTimeout, Duration.ofMillis(300));
        readTimeout = positiveOrDefault(readTimeout, Duration.ofSeconds(2));
        webhookSecret = webhookSecret == null || webhookSecret.isBlank()
                ? "local-webhook-secret"
                : webhookSecret;
        webhookMaxAge = positiveOrDefault(webhookMaxAge, Duration.ofMinutes(5));
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}