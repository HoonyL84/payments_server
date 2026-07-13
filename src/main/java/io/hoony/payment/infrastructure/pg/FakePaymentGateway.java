package io.hoony.payment.infrastructure.pg;

import io.hoony.payment.application.port.out.PaymentGateway;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FakePaymentGateway implements PaymentGateway {

    private final AtomicInteger approveCallCount = new AtomicInteger();
    private final AtomicInteger confirmApproveCallCount = new AtomicInteger();
    private final AtomicInteger cancelCallCount = new AtomicInteger();
    private final AtomicInteger confirmCancelCallCount = new AtomicInteger();
    private volatile PgApproveStatus nextApproveStatus = PgApproveStatus.APPROVED;
    private volatile PgConfirmApproveStatus nextConfirmApproveStatus = PgConfirmApproveStatus.APPROVED;
    private volatile CancellationStatus nextCancellationStatus = CancellationStatus.CANCELED;
    private volatile CancellationConfirmationStatus nextCancellationConfirmationStatus =
            CancellationConfirmationStatus.CANCELED;

    @Override
    public ApprovalResult approve(ApprovalRequest request) {
        PgApproveResult result = requestApproval(new PgApproveRequest(
                request.paymentId(),
                request.merchantId(),
                request.orderId(),
                request.amount()
        ));
        return switch (result.status()) {
            case APPROVED -> ApprovalResult.approved("fake-txn-" + request.providerRequestId());
            case DECLINED -> ApprovalResult.declined("DECLINED");
            case TIMED_OUT -> ApprovalResult.timedOut();
        };
    }

    @Override
    public ConfirmationResult confirmApprove(ConfirmationRequest request) {
        PgConfirmApproveResult result = requestConfirmation(new PgConfirmApproveRequest(request.paymentId()));
        return switch (result.status()) {
            case APPROVED -> ConfirmationResult.approved("fake-confirmed-txn-" + request.providerRequestId());
            case DECLINED -> ConfirmationResult.declined("DECLINED");
            case UNKNOWN -> ConfirmationResult.unknown();
        };
    }

    @Override
    public CancellationResult cancel(CancellationRequest request) {
        cancelCallCount.incrementAndGet();
        return switch (nextCancellationStatus) {
            case CANCELED -> CancellationResult.canceled("fake-cancel-txn-" + request.providerRequestId());
            case DECLINED -> CancellationResult.declined("DECLINED");
            case TIMED_OUT -> CancellationResult.timedOut();
        };
    }

    @Override
    public CancellationConfirmationResult confirmCancel(CancellationConfirmationRequest request) {
        confirmCancelCallCount.incrementAndGet();
        return switch (nextCancellationConfirmationStatus) {
            case CANCELED -> CancellationConfirmationResult.canceled(
                    "fake-confirmed-cancel-txn-" + request.providerRequestId()
            );
            case DECLINED -> CancellationConfirmationResult.declined("DECLINED");
            case UNKNOWN -> CancellationConfirmationResult.unknown();
        };
    }

    private PgApproveResult requestApproval(PgApproveRequest request) {
        approveCallCount.incrementAndGet();
        return switch (nextApproveStatus) {
            case APPROVED -> PgApproveResult.approved();
            case DECLINED -> PgApproveResult.declined();
            case TIMED_OUT -> PgApproveResult.timedOut();
        };
    }

    private PgConfirmApproveResult requestConfirmation(PgConfirmApproveRequest request) {
        confirmApproveCallCount.incrementAndGet();
        return switch (nextConfirmApproveStatus) {
            case APPROVED -> PgConfirmApproveResult.approved();
            case DECLINED -> PgConfirmApproveResult.declined();
            case UNKNOWN -> PgConfirmApproveResult.unknown();
        };
    }

    public int approveCallCount() {
        return approveCallCount.get();
    }

    public int confirmApproveCallCount() {
        return confirmApproveCallCount.get();
    }

    public int cancelCallCount() {
        return cancelCallCount.get();
    }

    public int confirmCancelCallCount() {
        return confirmCancelCallCount.get();
    }

    public void nextApproveStatus(PgApproveStatus status) {
        this.nextApproveStatus = status;
    }

    public void nextConfirmApproveStatus(PgConfirmApproveStatus status) {
        this.nextConfirmApproveStatus = status;
    }

    public void nextCancellationStatus(CancellationStatus status) {
        this.nextCancellationStatus = status;
    }

    public void nextCancellationConfirmationStatus(CancellationConfirmationStatus status) {
        this.nextCancellationConfirmationStatus = status;
    }
}