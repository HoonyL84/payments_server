package io.hoony.payment.presentation.payment;

import io.hoony.payment.application.confirmation.ConfirmPaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/payments")
public class InternalPaymentConfirmationController {

    private final ConfirmPaymentService confirmPaymentService;

    public InternalPaymentConfirmationController(ConfirmPaymentService confirmPaymentService) {
        this.confirmPaymentService = confirmPaymentService;
    }

    @PostMapping("/{paymentId}/confirm")
    public ResponseEntity<ConfirmPaymentHttpResponse> confirm(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(ConfirmPaymentHttpResponse.from(confirmPaymentService.confirm(paymentId)));
    }
}
