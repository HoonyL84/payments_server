package io.hoony.payment.domain.merchant;

import io.hoony.payment.domain.common.ResourceConflictException;

import java.util.Objects;

public record MerchantContract(
        String id,
        MerchantStatus status,
        String provider,
        String routingKey
) {

    public MerchantContract {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required.");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required.");
        }
        if (routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("routingKey is required.");
        }
    }

    public void requireActive() {
        if (status != MerchantStatus.ACTIVE) {
            throw new ResourceConflictException("Merchant is not active.");
        }
    }
}
