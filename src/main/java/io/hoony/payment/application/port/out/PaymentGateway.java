package io.hoony.payment.application.port.out;

import io.hoony.payment.domain.money.Money;

import java.util.Objects;
import java.util.UUID;

public interface PaymentGateway {

    ApprovalResult approve(ApprovalRequest request);

    ConfirmationResult confirmApprove(ConfirmationRequest request);

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
            Objects.requireNonNull(merchantId, "merchantId must not be null");
            Objects.requireNonNull(orderId, "orderId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            requireText(provider, "provider");
            requireText(routingKey, "routingKey");
            requireText(providerRequestId, "providerRequestId");
        }
    }

    record ApprovalResult(
            ApprovalStatus status,
            String providerTransactionId,
            String errorCode
    ) {
        public ApprovalResult {
            Objects.requireNonNull(status, "status must not be null");
        }

        public static ApprovalResult approved(String providerTransactionId) {
            return new ApprovalResult(
                    ApprovalStatus.APPROVED,
                    providerTransactionId,
                    null
            );
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

    record ConfirmationResult(ConfirmationStatus status) {
        public ConfirmationResult {
            Objects.requireNonNull(status, "status must not be null");
        }

        public static ConfirmationResult approved() {
            return new ConfirmationResult(ConfirmationStatus.APPROVED);
        }

        public static ConfirmationResult declined() {
            return new ConfirmationResult(ConfirmationStatus.DECLINED);
        }

        public static ConfirmationResult unknown() {
            return new ConfirmationResult(ConfirmationStatus.UNKNOWN);
        }
    }

    enum ConfirmationStatus {
        APPROVED,
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
