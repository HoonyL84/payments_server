package io.hoony.paymentconsumer;

/**
 * Routes an aggregate event that violates Kafka key ordering to retry and DLT handling.
 */
public final class OutOfOrderPaymentEventException extends RuntimeException {

    public OutOfOrderPaymentEventException(String message) {
        super(message);
    }
}
