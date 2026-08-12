package io.hoony.payment.infrastructure.pg.webhook;

import io.hoony.payment.config.PgClientProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class PgWebhookSignatureVerifierTest {

    private static final String SECRET = "webhook-secret";
    private static final Instant NOW = Instant.parse("2026-07-31T10:00:00Z");

    private final PgWebhookSignatureVerifier verifier = new PgWebhookSignatureVerifier(
            new PgClientProperties(
                    "http",
                    URI.create("http://localhost:8090"),
                    Duration.ofMillis(100),
                    Duration.ofSeconds(1),
                    SECRET,
                    Duration.ofMinutes(5)
            ),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void acceptsValidRecentSignature() throws Exception {
        String timestamp = Long.toString(NOW.minusSeconds(10).getEpochSecond());
        String body = "{\"eventId\":\"event-1\"}";

        assertThat(verifier.verify(timestamp, sign(timestamp, body), body)).isTrue();
    }

    @Test
    void rejectsExpiredOrModifiedPayload() throws Exception {
        String expired = Long.toString(NOW.minus(Duration.ofMinutes(6)).getEpochSecond());
        String recent = Long.toString(NOW.getEpochSecond());
        String body = "{\"eventId\":\"event-1\"}";

        assertThat(verifier.verify(expired, sign(expired, body), body)).isFalse();
        assertThat(verifier.verify(recent, sign(recent, body), body + " ")).isFalse();
    }

    private static String sign(String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8))
        );
    }
}