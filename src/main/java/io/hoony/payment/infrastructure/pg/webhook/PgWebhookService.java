package io.hoony.payment.infrastructure.pg.webhook;

import io.hoony.payment.application.cancellation.ConfirmCancellationService;
import io.hoony.payment.application.confirmation.ConfirmPaymentService;
import io.hoony.payment.domain.common.DomainException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class PgWebhookService {

    private final PgWebhookReceiptRepository receipts;
    private final ConfirmPaymentService confirmPayment;
    private final ConfirmCancellationService confirmCancellation;
    private final Clock clock;

    public PgWebhookService(
            PgWebhookReceiptRepository receipts,
            ConfirmPaymentService confirmPayment,
            ConfirmCancellationService confirmCancellation,
            Clock clock
    ) {
        this.receipts = receipts;
        this.confirmPayment = confirmPayment;
        this.confirmCancellation = confirmCancellation;
        this.clock = clock;
    }

    public Result handle(PgWebhookEvent event) {
        Instant now = Instant.now(clock);
        if (!receipts.claim(event.eventId(), event.providerRequestId(), event.operation(), now)) {
            return Result.DUPLICATE;
        }

        try {
            if ("APPROVE".equals(event.operation())) {
                confirmPayment.confirm(event.paymentId());
            } else if ("CANCEL".equals(event.operation()) && event.cancellationId() != null) {
                confirmCancellation.confirm(event.paymentId(), event.cancellationId());
            } else {
                throw new IllegalArgumentException("Unsupported PG webhook operation.");
            }
            receipts.complete(event.eventId(), Instant.now(clock));
            return Result.PROCESSED;
        } catch (DomainException exception) {
            receipts.complete(event.eventId(), Instant.now(clock));
            return Result.IGNORED;
        } catch (RuntimeException exception) {
            receipts.release(event.eventId());
            throw exception;
        }
    }

    public record PgWebhookEvent(
            UUID eventId,
            String operation,
            UUID paymentId,
            UUID cancellationId,
            String providerRequestId,
            String status,
            Instant occurredAt
    ) {
    }

    public enum Result {
        PROCESSED,
        DUPLICATE,
        IGNORED
    }
}