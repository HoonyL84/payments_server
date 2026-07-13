package io.hoony.payment.application.approval;

import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import org.springframework.stereotype.Service;

@Service
public class ApprovePaymentService {

    private final ApprovalTransactionService transactions;
    private final PaymentGateway paymentGateway;

    public ApprovePaymentService(
            ApprovalTransactionService transactions,
            PaymentGateway paymentGateway
    ) {
        this.transactions = transactions;
        this.paymentGateway = paymentGateway;
    }

    public ApprovePaymentResult approve(ApprovePaymentCommand command) {
        String fingerprint = ApprovalRequestFingerprint.from(command);
        IdempotencyScope scope = new IdempotencyScope(
                command.merchantId(),
                IdempotencyOperation.APPROVE,
                command.idempotencyKey()
        );

        ApprovalPreparation preparation = transactions.prepare(command, scope, fingerprint);
        if (preparation.isReplay()) {
            return preparation.replayedResult();
        }

        PaymentGateway.ApprovalResult gatewayResult = paymentGateway.approve(
                new PaymentGateway.ApprovalRequest(
                        preparation.payment().id(),
                        command.merchantId(),
                        command.orderId(),
                        command.amount(),
                        preparation.provider(),
                        preparation.routingKey(),
                        preparation.attempt().providerRequestId()
                )
        );

        return transactions.complete(
                scope,
                preparation.payment().id(),
                preparation.attempt().id(),
                gatewayResult
        );
    }
}
