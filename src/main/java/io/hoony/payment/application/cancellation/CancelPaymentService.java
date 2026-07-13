package io.hoony.payment.application.cancellation;

import io.hoony.payment.application.port.out.PaymentGateway;
import org.springframework.stereotype.Service;

@Service
public class CancelPaymentService {

    private final CancellationTransactionService transactions;
    private final PaymentGateway paymentGateway;

    public CancelPaymentService(
            CancellationTransactionService transactions,
            PaymentGateway paymentGateway
    ) {
        this.transactions = transactions;
        this.paymentGateway = paymentGateway;
    }

    public CancelPaymentResult cancel(CancelPaymentCommand command) {
        CancellationPreparation preparation = transactions.prepare(
                command,
                CancellationRequestFingerprint.from(command)
        );
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