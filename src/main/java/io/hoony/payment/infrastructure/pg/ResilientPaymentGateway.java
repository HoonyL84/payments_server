package io.hoony.payment.infrastructure.pg;

import io.hoony.payment.application.port.out.PaymentGateway;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!test & !integration")
@Primary
@Component
public class ResilientPaymentGateway implements PaymentGateway {
    private final FakePaymentGateway delegate;
    private final ProviderCallExecutor calls;

    public ResilientPaymentGateway(FakePaymentGateway delegate, ProviderCallExecutor calls) {
        this.delegate = delegate;
        this.calls = calls;
    }

    @Override
    public ApprovalResult approve(ApprovalRequest request) {
        return calls.executeCommand(
                ProviderOperation.APPROVE,
                () -> delegate.approve(request),
                ApprovalResult::timedOut
        );
    }

    @Override
    public ConfirmationResult confirmApprove(ConfirmationRequest request) {
        return calls.executeInquiry(
                ProviderOperation.CONFIRM_APPROVE,
                () -> delegate.confirmApprove(request),
                result -> result.status() == ConfirmationStatus.UNKNOWN,
                ConfirmationResult::unknown
        );
    }

    @Override
    public CancellationResult cancel(CancellationRequest request) {
        return calls.executeCommand(
                ProviderOperation.CANCEL,
                () -> delegate.cancel(request),
                CancellationResult::timedOut
        );
    }

    @Override
    public CancellationConfirmationResult confirmCancel(CancellationConfirmationRequest request) {
        return calls.executeInquiry(
                ProviderOperation.CONFIRM_CANCEL,
                () -> delegate.confirmCancel(request),
                result -> result.status() == CancellationConfirmationStatus.UNKNOWN,
                CancellationConfirmationResult::unknown
        );
    }

    public void ensureAvailable(ProviderOperation operation) {
        calls.ensureAvailable(operation);
    }

    public void resetProtection() {
        calls.reset();
    }
}
