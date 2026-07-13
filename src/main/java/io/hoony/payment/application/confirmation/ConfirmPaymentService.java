package io.hoony.payment.application.confirmation;

import io.hoony.payment.application.approval.ApprovalTransactionService;
import io.hoony.payment.application.port.out.PaymentGateway;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ConfirmPaymentService {

    private final ApprovalTransactionService transactions;
    private final PaymentGateway paymentGateway;

    public ConfirmPaymentService(
            ApprovalTransactionService transactions,
            PaymentGateway paymentGateway
    ) {
        this.transactions = transactions;
        this.paymentGateway = paymentGateway;
    }

    public ConfirmPaymentResult confirm(UUID paymentId) {
        ConfirmationPreparation preparation = transactions.prepareConfirmation(paymentId);
        PaymentGateway.ConfirmationResult gatewayResult = paymentGateway.confirmApprove(
                new PaymentGateway.ConfirmationRequest(
                        paymentId,
                        preparation.provider(),
                        preparation.routingKey(),
                        preparation.providerRequestId()
                )
        );
        return transactions.completeConfirmation(preparation, gatewayResult);
    }
}
