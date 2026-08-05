package io.hoony.payment.application.cancellation;

import io.hoony.payment.application.admission.IdempotencyAdmission;
import io.hoony.payment.application.port.out.IdempotencyAdmissionGate;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.domain.idempotency.IdempotencyOperation;
import org.springframework.stereotype.Service;

@Service
public class CancelPaymentService {

    private final CancellationTransactionService transactions;
    private final PaymentGateway paymentGateway;
    private final IdempotencyAdmissionGate admissionGate;

    public CancelPaymentService(
            CancellationTransactionService transactions,
            PaymentGateway paymentGateway,
            IdempotencyAdmissionGate admissionGate
    ) {
        this.transactions = transactions;
        this.paymentGateway = paymentGateway;
        this.admissionGate = admissionGate;
    }

    public CancelPaymentResult cancel(CancelPaymentCommand command) {
        String fingerprint = CancellationRequestFingerprint.from(command);
        IdempotencyAdmission admission = new IdempotencyAdmission(
                IdempotencyOperation.CANCEL,
                command.paymentId().toString(),
                command.idempotencyKey(),
                fingerprint
        );
        return admissionGate.execute(admission, () -> cancelAfterAdmission(command, fingerprint));
    }

    private CancelPaymentResult cancelAfterAdmission(
            CancelPaymentCommand command,
            String fingerprint
    ) {
        CancellationPreparation preparation = transactions.prepare(command, fingerprint);
        if (preparation.isReplay()) {
            return preparation.replayedResult();
        }

        PaymentGateway.CancellationResult gatewayResult = paymentGateway.cancel(
                new PaymentGateway.CancellationRequest(
                        preparation.payment().id(),
                        preparation.cancellation().id(),
                        preparation.payment().merchantId(),
                        preparation.cancellation().amount(),
                        preparation.provider(),
                        preparation.routingKey(),
                        preparation.originalProviderTransactionId(),
                        preparation.attempt().providerRequestId()
                )
        );
        return transactions.complete(preparation, gatewayResult);
    }
}