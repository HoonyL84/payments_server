package io.hoony.payment.application.query;

import io.hoony.payment.application.port.out.PaymentRepository;
import io.hoony.payment.domain.common.ResourceNotFoundException;
import io.hoony.payment.domain.payment.Payment;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class GetPaymentService {

    private final PaymentRepository payments;

    public GetPaymentService(PaymentRepository payments) {
        this.payments = payments;
    }

    public PaymentStatusResult get(UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        return new PaymentStatusResult(payment.id(), payment.state());
    }
}
