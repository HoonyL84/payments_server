package io.hoony.mockpg;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class MockPgController {

    private final MockPgService service;
    private final PgTransactionStore transactions;

    public MockPgController(MockPgService service, PgTransactionStore transactions) {
        this.service = service;
        this.transactions = transactions;
    }

    @PostMapping("/approve")
    public PgResponse approve(@Valid @RequestBody PgCommandRequest request) {
        return PgResponse.from(service.execute(request.toCommand(), MockPgBehavior.Operation.APPROVE));
    }

    @PostMapping("/cancel")
    public PgResponse cancel(@Valid @RequestBody PgCommandRequest request) {
        return PgResponse.from(service.execute(request.toCommand(), MockPgBehavior.Operation.CANCEL));
    }

    @GetMapping("/requests/{providerRequestId}")
    public ResponseEntity<PgResponse> findByRequestId(
            @org.springframework.web.bind.annotation.PathVariable String providerRequestId
    ) {
        return transactions.find(providerRequestId)
                .map(PgResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public TransactionPage find(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        List<PgResponse> items = transactions.findAfter(from, cursor, limit)
                .stream()
                .map(PgResponse::from)
                .toList();
        String nextCursor = items.size() == limit ? items.getLast().providerRequestId() : null;
        return new TransactionPage(items, nextCursor);
    }

    @ExceptionHandler(PgTransactionStore.PayloadConflictException.class)
    ResponseEntity<ErrorResponse> payloadConflict(PgTransactionStore.PayloadConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("PROVIDER_REQUEST_CONFLICT", exception.getMessage()));
    }

    public record PgCommandRequest(
            @NotBlank String providerRequestId,
            @NotNull UUID paymentId,
            UUID cancellationId,
            @NotBlank String merchantId,
            String orderId,
            @Positive long amountMinorUnits,
            @NotBlank String currency,
            String originalProviderTransactionId
    ) {
        PgTransactionStore.Command toCommand() {
            return new PgTransactionStore.Command(
                    providerRequestId,
                    paymentId,
                    cancellationId,
                    merchantId,
                    orderId,
                    amountMinorUnits,
                    currency,
                    originalProviderTransactionId
            );
        }
    }

    public record PgResponse(
            String providerRequestId,
            String operation,
            String status,
            String providerTransactionId,
            String errorCode,
            Instant createdAt,
            UUID paymentId,
            UUID cancellationId
    ) {
        static PgResponse from(PgTransactionStore.Transaction transaction) {
            return new PgResponse(
                    transaction.providerRequestId(),
                    transaction.operation().name(),
                    transaction.status(),
                    transaction.providerTransactionId(),
                    transaction.errorCode(),
                    transaction.createdAt(),
                    transaction.paymentId(),
                    transaction.cancellationId()
            );
        }
    }

    public record TransactionPage(List<PgResponse> items, String nextCursor) {
    }

    public record ErrorResponse(String code, String message) {
    }
}