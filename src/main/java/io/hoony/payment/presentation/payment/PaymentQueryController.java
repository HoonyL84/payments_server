package io.hoony.payment.presentation.payment;

import io.hoony.payment.application.query.GetPaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentQueryController {

    private final GetPaymentService getPaymentService;

    public PaymentQueryController(GetPaymentService getPaymentService) {
        this.getPaymentService = getPaymentService;
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentStatusHttpResponse> get(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(PaymentStatusHttpResponse.from(getPaymentService.get(paymentId)));
    }
}
