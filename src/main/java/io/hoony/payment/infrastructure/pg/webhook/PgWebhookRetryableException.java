package io.hoony.payment.infrastructure.pg.webhook;

/**
 * Signals that a valid webhook arrived before the local payment state was ready.
 */
public final class PgWebhookRetryableException extends RuntimeException {

    public PgWebhookRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
