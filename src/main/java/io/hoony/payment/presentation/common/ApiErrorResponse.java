package io.hoony.payment.presentation.common;

import java.time.Instant;

public record ApiErrorResponse(
        String code,
        String message,
        String traceId,
        Instant occurredAt
) {
    public static ApiErrorResponse of(String code, String message, String traceId) {
        return new ApiErrorResponse(code, message, traceId, Instant.now());
    }
}