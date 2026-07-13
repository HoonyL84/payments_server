package io.hoony.payment.presentation.payment;

import io.hoony.payment.application.cancellation.CancelPaymentCommand;
import io.hoony.payment.application.cancellation.CancelPaymentResult;
import io.hoony.payment.application.cancellation.CancelPaymentService;
import io.hoony.payment.domain.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentCancellationController {

    private final CancelPaymentService cancelPaymentService;

    public PaymentCancellationController(CancelPaymentService cancelPaymentService) {
        this.cancelPaymentService = cancelPaymentService;
    }

    @PostMapping("/{paymentId}/cancel")
    public ResponseEntity<CancelPaymentHttpResponse> cancel(
            @PathVariable UUID paymentId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CancelPaymentHttpRequest request
    ) {
        CancelPaymentResult result = cancelPaymentService.cancel(new CancelPaymentCommand(
                idempotencyKey,
                paymentId,
                new Money(request.amountMinorUnits(), request.currency())
        ));
        return ResponseEntity.ok()
                .header(
                        PaymentApprovalController.IDEMPOTENCY_REPLAYED_HEADER,
                        Boolean.toString(result.reused())
                )
                .body(CancelPaymentHttpResponse.from(result));
    }
}