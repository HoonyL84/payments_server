package io.hoony.payment.presentation.payment;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelPaymentHttpRequest(
        @Min(1) long amountMinorUnits,
        @NotBlank @Size(min = 3, max = 3) String currency
) {
}