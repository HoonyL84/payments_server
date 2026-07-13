package io.hoony.payment.presentation.payment;

import io.hoony.payment.application.cancellation.ConfirmCancellationResult;
import io.hoony.payment.application.cancellation.ConfirmCancellationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/payments")
public class InternalCancellationConfirmationController {

    private final ConfirmCancellationService confirmCancellationService;

    public InternalCancellationConfirmationController(
            ConfirmCancellationService confirmCancellationService
    ) {
        this.confirmCancellationService = confirmCancellationService;
    }

    @PostMapping("/{paymentId}/cancellations/{cancellationId}/confirm")
    public ResponseEntity<ConfirmCancellationHttpResponse> confirm(
            @PathVariable UUID paymentId,
            @PathVariable UUID cancellationId
    ) {
        ConfirmCancellationResult result = confirmCancellationService.confirm(paymentId, cancellationId);
        return ResponseEntity.ok(ConfirmCancellationHttpResponse.from(result));
    }
}