package io.hoony.payment.application.cancellation;

import io.hoony.payment.application.port.out.PaymentGateway;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ConfirmCancellationService {

    private final CancellationTransactionService transactions;
    private final PaymentGateway paymentGateway;

    public ConfirmCancellationService(
            CancellationTransactionService transactions,
            PaymentGateway paymentGateway
    ) {
        this.transactions = transactions;
        this.paymentGateway = paymentGateway;
    }

    public ConfirmCancellationResult confirm(UUID paymentId, UUID cancellationId) {
        CancellationConfirmationPreparation preparation = transactions.prepareConfirmation(
                paymentId,
                cancellationId
        );
        PaymentGateway.CancellationConfirmationResult gatewayResult = paymentGateway.confirmCancel(
                new PaymentGateway.CancellationConfirmationRequest(
                        paymentId,
                        cancellationId,
                        preparation.provider(),
                        preparation.routingKey(),
                        preparation.originalProviderRequestId()
                )
        );
        return transactions.completeConfirmation(preparation, gatewayResult);
    }
}