package io.hoony.mockpg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class PgWebhookSender {

    private static final Logger log = LoggerFactory.getLogger(PgWebhookSender.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final RestClient client;
    private final TaskScheduler scheduler;
    private final ObjectMapper objectMapper;
    private final String secret;

    public PgWebhookSender(
            RestClient.Builder builder,
            TaskScheduler scheduler,
            ObjectMapper objectMapper,
            @Value("${mock-pg.webhook.url}") String webhookUrl,
            @Value("${mock-pg.webhook.secret}") String secret
    ) {
        this.client = builder.baseUrl(webhookUrl).build();
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
        this.secret = secret;
    }

    public void schedule(PgTransactionStore.Transaction transaction, Duration delay) {
        scheduler.schedule(() -> send(transaction), Instant.now().plus(delay));
    }

    private void send(PgTransactionStore.Transaction transaction) {
        try {
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String body = objectMapper.writeValueAsString(new WebhookEvent(
                    UUID.randomUUID(),
                    transaction.operation().name(),
                    transaction.paymentId(),
                    transaction.cancellationId(),
                    transaction.providerRequestId(),
                    transaction.status(),
                    Instant.now()
            ));
            client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-PG-Timestamp", timestamp)
                    .header("X-PG-Signature", sign(timestamp, body))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException exception) {
            log.warn("Mock PG webhook delivery failed. providerRequestId={}",
                    transaction.providerRequestId(), exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Webhook signing failed.", exception);
        }
    }

    private String sign(String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        return HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8))
        );
    }

    record WebhookEvent(
            UUID eventId,
            String operation,
            UUID paymentId,
            UUID cancellationId,
            String providerRequestId,
            String status,
            Instant occurredAt
    ) {
    }
}