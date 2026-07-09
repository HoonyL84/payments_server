package io.hoony.payment.presentation.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ApprovePaymentHttpRequest(
        @NotBlank String userId,
        @NotBlank String merchantId,
        @NotBlank String orderId,
        @Min(1) long amountMinorUnits,
        @NotBlank String currency
) {
}