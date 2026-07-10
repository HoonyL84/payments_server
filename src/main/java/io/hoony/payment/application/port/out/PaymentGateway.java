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
            Money amount
    ) {
        public ApprovalRequest {
            Objects.requireNonNull(paymentId, "paymentId must not be null");
            Objects.requireNonNull(merchantId, "merchantId must not be null");
            Objects.requireNonNull(orderId, "orderId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }

    record ApprovalResult(ApprovalStatus status) {
        public ApprovalResult {
            Objects.requireNonNull(status, "status must not be null");
        }

        public static ApprovalResult approved() {
            return new ApprovalResult(ApprovalStatus.APPROVED);
        }

        public static ApprovalResult declined() {
            return new ApprovalResult(ApprovalStatus.DECLINED);
        }

        public static ApprovalResult timedOut() {
            return new ApprovalResult(ApprovalStatus.TIMED_OUT);
        }
    }

    enum ApprovalStatus {
        APPROVED,
        DECLINED,
        TIMED_OUT
    }

    record ConfirmationRequest(UUID paymentId) {
        public ConfirmationRequest {
            Objects.requireNonNull(paymentId, "paymentId must not be null");
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
}
