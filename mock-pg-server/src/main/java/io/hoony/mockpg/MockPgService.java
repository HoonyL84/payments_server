package io.hoony.mockpg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.locks.LockSupport;

@Service
public class MockPgService {

    private final PgTransactionStore transactions;
    private final MockPgBehavior behavior;
    private final PgWebhookSender webhooks;
    private final ObjectMapper objectMapper;

    public MockPgService(
            PgTransactionStore transactions,
            MockPgBehavior behavior,
            PgWebhookSender webhooks,
            ObjectMapper objectMapper
    ) {
        this.transactions = transactions;
        this.behavior = behavior;
        this.webhooks = webhooks;
        this.objectMapper = objectMapper;
    }

    public PgTransactionStore.Transaction execute(
            PgTransactionStore.Command command,
            MockPgBehavior.Operation operation
    ) {
        MockPgBehavior.Snapshot snapshot = behavior.snapshot(operation);
        if (snapshot.mode() == MockPgBehavior.Mode.CONNECTION_FAILURE) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated connection failure.");
        }

        String status = status(operation, snapshot.mode());
        PgTransactionStore.Transaction transaction = transactions.saveOrGet(
                command,
                operation,
                fingerprint(command, operation),
                status
        );
        if (snapshot.webhookEnabled() || responseLost(snapshot.mode())) {
            webhooks.schedule(transaction, snapshot.webhookDelay());
        }
        delay(snapshot.responseDelay());
        if (responseLost(snapshot.mode())) {
            delay(Duration.ofSeconds(5));
        }
        return transaction;
    }

    private String fingerprint(PgTransactionStore.Command command, MockPgBehavior.Operation operation) {
        try {
            String source = operation + "|" + objectMapper.writeValueAsString(command);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("PG command cannot be serialized.", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String status(MockPgBehavior.Operation operation, MockPgBehavior.Mode mode) {
        if (mode == MockPgBehavior.Mode.DECLINED) {
            return "DECLINED";
        }
        return operation == MockPgBehavior.Operation.APPROVE ? "APPROVED" : "CANCELED";
    }

    private static boolean responseLost(MockPgBehavior.Mode mode) {
        return mode == MockPgBehavior.Mode.APPROVED_RESPONSE_LOST
                || mode == MockPgBehavior.Mode.CANCELED_RESPONSE_LOST;
    }

    private static void delay(Duration duration) {
        if (!duration.isZero()) {
            LockSupport.parkNanos(duration.toNanos());
        }
    }
}