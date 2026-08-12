package io.hoony.payment.presentation.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hoony.payment.infrastructure.pg.webhook.PgWebhookService;
import io.hoony.payment.infrastructure.pg.webhook.PgWebhookSignatureVerifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pg/webhooks")
public class PgWebhookController {

    private final PgWebhookSignatureVerifier signatures;
    private final PgWebhookService webhooks;
    private final ObjectMapper objectMapper;

    public PgWebhookController(
            PgWebhookSignatureVerifier signatures,
            PgWebhookService webhooks,
            ObjectMapper objectMapper
    ) {
        this.signatures = signatures;
        this.webhooks = webhooks;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<WebhookResponse> receive(
            @RequestHeader("X-PG-Timestamp") String timestamp,
            @RequestHeader("X-PG-Signature") String signature,
            @RequestBody String body
    ) throws JsonProcessingException {
        if (!signatures.verify(timestamp, signature, body)) {
            return ResponseEntity.status(401).build();
        }
        PgWebhookService.PgWebhookEvent event =
                objectMapper.readValue(body, PgWebhookService.PgWebhookEvent.class);
        return ResponseEntity.accepted().body(new WebhookResponse(webhooks.handle(event).name()));
    }

    public record WebhookResponse(String result) {
    }
}