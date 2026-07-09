package io.hoony.payment.presentation.common;

import io.hoony.payment.domain.common.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("VALIDATION_ERROR", message, currentTraceId()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("VALIDATION_ERROR", exception.getMessage(), currentTraceId()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingRequestHeader(MissingRequestHeaderException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("VALIDATION_ERROR", exception.getHeaderName() + " header is required", currentTraceId()));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainException(DomainException exception) {
        return ResponseEntity.badRequest()
                .body(ApiErrorResponse.of("DOMAIN_ERROR", exception.getMessage(), currentTraceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_SERVER_ERROR", "Unexpected server error", currentTraceId()));
    }

    private String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        return traceId == null ? "unknown" : traceId;
    }
}