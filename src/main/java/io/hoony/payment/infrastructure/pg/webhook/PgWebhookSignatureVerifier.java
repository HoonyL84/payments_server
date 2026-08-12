package io.hoony.payment.infrastructure.pg.webhook;

import io.hoony.payment.config.PgClientProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class PgWebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    private final PgClientProperties properties;
    private final Clock clock;

    public PgWebhookSignatureVerifier(PgClientProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean verify(String timestamp, String signature, String payload) {
        if (timestamp == null || signature == null || payload == null) {
            return false;
        }
        try {
            Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
            Duration age = Duration.between(signedAt, Instant.now(clock));
            if (age.isNegative() && age.abs().compareTo(Duration.ofSeconds(30)) > 0) {
                return false;
            }
            if (!age.isNegative() && age.compareTo(properties.webhookMaxAge()) > 0) {
                return false;
            }

            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.webhookSecret().getBytes(StandardCharsets.UTF_8),
                    ALGORITHM
            ));
            byte[] expected = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            byte[] actual = HexFormat.of().parseHex(signature);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception exception) {
            return false;
        }
    }
}