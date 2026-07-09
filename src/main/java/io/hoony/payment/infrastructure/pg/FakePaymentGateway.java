package io.hoony.payment.infrastructure.pg;

import io.hoony.payment.application.port.out.PaymentGateway;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FakePaymentGateway implements PaymentGateway {

    private final AtomicInteger approveCallCount = new AtomicInteger();

    @Override
    public PgApproveResult approve(PgApproveRequest request) {
        approveCallCount.incrementAndGet();
        return PgApproveResult.approved();
    }

    public int approveCallCount() {
        return approveCallCount.get();
    }
}