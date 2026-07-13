package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.money.Money;

import java.util.Objects;
import java.util.UUID;

public interface PaymentGateway {

    ApprovalResult approve(ApprovalRequest request);

    ConfirmationResult confirmApprove(ConfirmationRequest request);

    CancellationResult cancel(CancellationRequest request);

    CancellationConfirmationResult confirmCancel(CancellationConfirmationRequest request);

    record ApprovalRequest(
            UUID paymentId,
            String merchantId,
            String orderId,
            Money amount,
            String provider,
            String routingKey,
            String providerRequestId
    ) {
        public ApprovalRequest {
            Objects.requireNonNull(paymentId, "paymentId must not be null");
            requireText(merchantId, "merchantId");
            requireText(orderId, "orderId");
            Objects.requireNonNull(amount, "amount must not be null");
            requireText(provider, "provider");
            requireText(routingKey, "routingKey");
            requireText(providerRequestId, "providerRequestId");
        }
    }

    record ApprovalResult(ApprovalStatus status, String providerTransactionId, String errorCode) {
        public ApprovalResult {
            Objects.requireNonNull(status, "status must not be null");
        }

        public static ApprovalResult approved(String providerTransactionId) {
            return new ApprovalResult(ApprovalStatus.APPROVED, providerTransactionId, null);
        }

        public static ApprovalResult declined(String errorCode) {
            return new ApprovalResult(ApprovalStatus.DECLINED, null, errorCode);
        }

        public static ApprovalResult timedOut() {
            return new ApprovalResult(ApprovalStatus.TIMED_OUT, null, "TIMEOUT");
        }
    }

    enum ApprovalStatus {
        APPROVED,
        DECLINED,
        TIMED_OUT
    }

    record ConfirmationRequest(
            UUID paymentId,
            String provider,
            String routingKey,
            String providerRequestId
    ) {
        public ConfirmationRequest {
            Objects.requireNonNull(paymentId, "paymentId must not be null");
            requireText(provider, "provider");
            requireText(routingKey, "routingKey");
            requireText(providerRequestId, "providerRequestId");
        }
    }

    record ConfirmationResult(
            ConfirmationStatus status,
            String providerTransactionId,
            String errorCode
    ) {
        public ConfirmationResult {
            Objects.requireNonNull(status, "status must not be null");
        }

        public static ConfirmationResult approved(String providerTransactionId) {
            return new ConfirmationResult(ConfirmationStatus.APPROVED, providerTransactionId, null);
        }

        public static ConfirmationResult declined(String errorCode) {
            return new ConfirmationResult(ConfirmationStatus.DECLINED, null, errorCode);
        }

        public static ConfirmationResult unknown() {
            return new ConfirmationResult(ConfirmationStatus.UNKNOWN, null, "UNKNOWN");
        }
    }

    enum ConfirmationStatus {
        APPROVED,
        DECLINED,
        UNKNOWN
    }

    record CancellationRequest(
            UUID paymentId,
            UUID cancellationId,
            String merchantId,
            Money amount,
            String provider,
            String routingKey,
            String originalProviderTransactionId,
            String providerRequestId
    ) {
        public CancellationRequest {
            Objects.requireNonNull(paymentId, "paymentId must not be null");
            Objects.requireNonNull(cancellationId, "cancellationId must not be null");
            requireText(merchantId, "merchantId");
            Objects.requireNonNull(amount, "amount must not be null");
            requireText(provider, "provider");
            requireText(routingKey, "routingKey");
            requireText(originalProviderTransactionId, "originalProviderTransactionId");
            requireText(providerRequestId, "providerRequestId");
        }
    }

    record CancellationResult(
            CancellationStatus status,
            String providerTransactionId,
            String errorCode
    ) {
        public CancellationResult {
            Objects.requireNonNull(status, "status must not be null");
        }

        public static CancellationResult canceled(String providerTransactionId) {
            return new CancellationResult(CancellationStatus.CANCELED, providerTransactionId, null);
        }

        public static CancellationResult declined(String errorCode) {
            return new CancellationResult(CancellationStatus.DECLINED, null, errorCode);
        }

        public static CancellationResult timedOut() {
            return new CancellationResult(CancellationStatus.TIMED_OUT, null, "TIMEOUT");
        }
    }

    enum CancellationStatus {
        CANCELED,
        DECLINED,
        TIMED_OUT
    }

    record CancellationConfirmationRequest(
            UUID paymentId,
            UUID cancellationId,
            String provider,
            String routingKey,
            String providerRequestId
    ) {
        public CancellationConfirmationRequest {
            Objects.requireNonNull(paymentId, "paymentId must not be null");
            Objects.requireNonNull(cancellationId, "cancellationId must not be null");
            requireText(provider, "provider");
            requireText(routingKey, "routingKey");
            requireText(providerRequestId, "providerRequestId");
        }
    }

    record CancellationConfirmationResult(
            CancellationConfirmationStatus status,
            String providerTransactionId,
            String errorCode
    ) {
        public CancellationConfirmationResult {
            Objects.requireNonNull(status, "status must not be null");
        }

        public static CancellationConfirmationResult canceled(String providerTransactionId) {
            return new CancellationConfirmationResult(
                    CancellationConfirmationStatus.CANCELED,
                    providerTransactionId,
                    null
            );
        }

        public static CancellationConfirmationResult declined(String errorCode) {
            return new CancellationConfirmationResult(
                    CancellationConfirmationStatus.DECLINED,
                    null,
                    errorCode
            );
        }

        public static CancellationConfirmationResult unknown() {
            return new CancellationConfirmationResult(
                    CancellationConfirmationStatus.UNKNOWN,
                    null,
                    "UNKNOWN"
            );
        }
    }

    enum CancellationConfirmationStatus {
        CANCELED,
        DECLINED,
        UNKNOWN
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value;
    }
}