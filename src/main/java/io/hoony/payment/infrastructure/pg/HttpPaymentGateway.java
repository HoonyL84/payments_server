package io.hoony.payment.infrastructure.pg;

import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.config.PgClientProperties;
import io.hoony.payment.domain.common.ResourceConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "payments.pg.mode", havingValue = "http")
public class HttpPaymentGateway implements PaymentGateway {

    private final RestClient client;

    @Autowired
    public HttpPaymentGateway(PgClientProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    HttpPaymentGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public ApprovalResult approve(ApprovalRequest request) {
        try {
            PgResponse response = post("/api/v1/transactions/approve", new PgCommand(
                    request.providerRequestId(),
                    request.paymentId(),
                    null,
                    request.merchantId(),
                    request.orderId(),
                    request.amount().minorUnits(),
                    request.amount().currency(),
                    null
            ));
            return switch (response.status()) {
                case "APPROVED" -> ApprovalResult.approved(response.providerTransactionId());
                case "DECLINED" -> ApprovalResult.declined(response.errorCode());
                default -> ApprovalResult.timedOut();
            };
        } catch (RestClientException exception) {
            return ApprovalResult.timedOut();
        }
    }

    @Override
    public ConfirmationResult confirmApprove(ConfirmationRequest request) {
        try {
            PgResponse response = get(request.providerRequestId());
            return switch (response.status()) {
                case "APPROVED" -> ConfirmationResult.approved(response.providerTransactionId());
                case "DECLINED" -> ConfirmationResult.declined(response.errorCode());
                default -> ConfirmationResult.unknown();
            };
        } catch (RestClientException exception) {
            return ConfirmationResult.unknown();
        }
    }

    @Override
    public CancellationResult cancel(CancellationRequest request) {
        try {
            PgResponse response = post("/api/v1/transactions/cancel", new PgCommand(
                    request.providerRequestId(),
                    request.paymentId(),
                    request.cancellationId(),
                    request.merchantId(),
                    null,
                    request.amount().minorUnits(),
                    request.amount().currency(),
                    request.originalProviderTransactionId()
            ));
            return switch (response.status()) {
                case "CANCELED" -> CancellationResult.canceled(response.providerTransactionId());
                case "DECLINED" -> CancellationResult.declined(response.errorCode());
                default -> CancellationResult.timedOut();
            };
        } catch (RestClientException exception) {
            return CancellationResult.timedOut();
        }
    }

    @Override
    public CancellationConfirmationResult confirmCancel(CancellationConfirmationRequest request) {
        try {
            PgResponse response = get(request.providerRequestId());
            return switch (response.status()) {
                case "CANCELED" -> CancellationConfirmationResult.canceled(response.providerTransactionId());
                case "DECLINED" -> CancellationConfirmationResult.declined(response.errorCode());
                default -> CancellationConfirmationResult.unknown();
            };
        } catch (RestClientException exception) {
            return CancellationConfirmationResult.unknown();
        }
    }

    private PgResponse post(String path, PgCommand command) {
        PgResponse response = client.post()
                .uri(path)
                .body(command)
                .retrieve()
                .onStatus(status -> status.value() == 409, (request, providerResponse) -> {
                    throw new ResourceConflictException("PG provider request payload conflicts.");
                })
                .body(PgResponse.class);
        if (response == null) {
            throw new IllegalStateException("PG returned an empty response.");
        }
        return response;
    }

    private PgResponse get(String providerRequestId) {
        PgResponse response = client.get()
                .uri("/api/v1/transactions/requests/{providerRequestId}", providerRequestId)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, result) -> {
                    throw new UnknownPgResultException();
                })
                .body(PgResponse.class);
        if (response == null) {
            throw new UnknownPgResultException();
        }
        return response;
    }

    record PgCommand(
            String providerRequestId,
            UUID paymentId,
            UUID cancellationId,
            String merchantId,
            String orderId,
            long amountMinorUnits,
            String currency,
            String originalProviderTransactionId
    ) {
    }

    record PgResponse(
            String providerRequestId,
            String operation,
            String status,
            String providerTransactionId,
            String errorCode
    ) {
    }

    private static final class UnknownPgResultException extends RestClientException {
        private UnknownPgResultException() {
            super("PG transaction is unknown.");
        }
    }
}