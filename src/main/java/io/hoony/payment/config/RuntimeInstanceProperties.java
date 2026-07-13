package io.hoony.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payments.runtime")
public record RuntimeInstanceProperties(String instanceId) {
    public RuntimeInstanceProperties {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("Payment runtime instance id is required.");
        }
    }
}
