package io.hoony.payment.infrastructure.pg.webhook;

import io.hoony.payment.application.cancellation.ConfirmCancellationService;
import io.hoony.payment.application.confirmation.ConfirmPaymentService;
import io.hoony.payment.domain.common.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PgWebhookServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

    @Mock
    private PgWebhookReceiptRepository receipts;

    @Mock
    private ConfirmPaymentService confirmPayment;

    @Mock
    private ConfirmCancellationService confirmCancellation;

    @Test
    void releasesReceiptWhenLocalPaymentIsNotReady() {
        PgWebhookService service = new PgWebhookService(
                receipts,
                confirmPayment,
                confirmCancellation,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        PgWebhookService.PgWebhookEvent event = new PgWebhookService.PgWebhookEvent(
                UUID.randomUUID(),
                "APPROVE",
                UUID.randomUUID(),
                null,
                "provider-request-1",
                "APPROVED",
                NOW
        );
        when(receipts.claim(event.eventId(), event.providerRequestId(), event.operation(), NOW))
                .thenReturn(true);
        when(confirmPayment.confirm(event.paymentId()))
                .thenThrow(new ResourceNotFoundException("Payment not found."));

        assertThatThrownBy(() -> service.handle(event))
                .isInstanceOf(PgWebhookRetryableException.class)
                .hasCauseInstanceOf(ResourceNotFoundException.class);

        verify(receipts).release(event.eventId());
        verify(receipts, never()).complete(event.eventId(), NOW);
    }
}
