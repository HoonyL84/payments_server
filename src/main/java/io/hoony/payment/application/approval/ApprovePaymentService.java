package io.hoony.payment.application.approval;

import io.hoony.payment.application.admission.IdempotencyAdmission;
import io.hoony.payment.application.port.out.IdempotencyAdmissionGate;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import io.hoony.payment.domain.idempotency.IdempotencyScope;
import org.springframework.stereotype.Service;

@Service
public class ApprovePaymentService {

    private final ApprovalTransactionService transactions;
    private final PaymentGateway paymentGateway;
    private final IdempotencyAdmissionGate admissionGate;

    public ApprovePaymentService(
            ApprovalTransactionService transactions,
            PaymentGateway paymentGateway,
            IdempotencyAdmissionGate admissionGate
    ) {
        this.transactions = transactions;
        this.paymentGateway = paymentGateway;
        this.admissionGate = admissionGate;
    }

    public ApprovePaymentResult approve(ApprovePaymentCommand command) {
        String fingerprint = ApprovalRequestFingerprint.from(command);
        IdempotencyScope scope = new IdempotencyScope(
                command.merchantId(),
                IdempotencyOperation.APPROVE,
                command.idempotencyKey()
        );
        IdempotencyAdmission admission = new IdempotencyAdmission(
                IdempotencyOperation.APPROVE,
                command.merchantId(),
                command.idempotencyKey(),
                fingerprint
        );
        return admissionGate.execute(
                admission,
                () -> approveAfterAdmission(command, scope, fingerprint)
        );
    }

    private ApprovePaymentResult approveAfterAdmission(
            ApprovePaymentCommand command,
            IdempotencyScope scope,
            String fingerprint
    ) {
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
