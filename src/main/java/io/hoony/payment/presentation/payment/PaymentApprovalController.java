package io.hoony.payment.presentation.payment;

import io.hoony.payment.application.approval.ApprovePaymentCommand;
import io.hoony.payment.application.approval.ApprovePaymentResult;
import io.hoony.payment.application.approval.ApprovePaymentService;
import io.hoony.payment.domain.money.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentApprovalController {

    public static final String IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed";

    private final ApprovePaymentService approvePaymentService;

    public PaymentApprovalController(ApprovePaymentService approvePaymentService) {
        this.approvePaymentService = approvePaymentService;
    }

    @PostMapping("/approve")
    public ResponseEntity<ApprovePaymentHttpResponse> approve(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody ApprovePaymentHttpRequest request
    ) {
        ApprovePaymentResult result = approvePaymentService.approve(new ApprovePaymentCommand(
                idempotencyKey,
                request.userId(),
                request.merchantId(),
                request.orderId(),
                new Money(request.amountMinorUnits(), request.currency())
        ));
        return ResponseEntity.ok()
                .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(result.reused()))
                .body(ApprovePaymentHttpResponse.from(result));
    }
}
