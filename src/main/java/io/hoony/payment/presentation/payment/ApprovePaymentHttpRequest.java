package io.hoony.payment.presentation.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ApprovePaymentHttpRequest(
        @NotBlank @Size(max = 64) String userId,
        @NotBlank @Size(max = 64) String merchantId,
        @NotBlank @Size(max = 128) String orderId,
        @Min(1) long amountMinorUnits,
        @NotBlank @Size(min = 3, max = 3) String currency
) {
}