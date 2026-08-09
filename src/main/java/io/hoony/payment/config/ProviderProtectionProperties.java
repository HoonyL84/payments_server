package io.hoony.payment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payments.provider-protection")
public record ProviderProtectionProperties(
        int commandMaxConcurrent,
        int inquiryMaxConcurrent,
        int inquiryQueueCapacity,
        int maxConcurrentRetries,
        Duration commandTimeout,
        Duration inquiryTimeout,
        Duration inquiryRequestBudget,
        int inquiryMaxAttempts,
        Duration retryInitialBackoff,
        int circuitSlidingWindowSize,
        int circuitMinimumCalls,
        float circuitFailureRateThreshold,
        Duration circuitOpenDuration,
        int circuitHalfOpenCalls
) {
    public ProviderProtectionProperties {
        requirePositive(commandMaxConcurrent, "commandMaxConcurrent");
        requirePositive(inquiryMaxConcurrent, "inquiryMaxConcurrent");
        requireNonNegative(inquiryQueueCapacity, "inquiryQueueCapacity");
        requirePositive(maxConcurrentRetries, "maxConcurrentRetries");
        requirePositive(commandTimeout, "commandTimeout");
        requirePositive(inquiryTimeout, "inquiryTimeout");
        requirePositive(inquiryRequestBudget, "inquiryRequestBudget");
        requirePositive(inquiryMaxAttempts, "inquiryMaxAttempts");
        requireNonNegative(retryInitialBackoff, "retryInitialBackoff");
        requirePositive(circuitSlidingWindowSize, "circuitSlidingWindowSize");
        requirePositive(circuitMinimumCalls, "circuitMinimumCalls");
        if (circuitMinimumCalls > circuitSlidingWindowSize) {
            throw new IllegalArgumentException("circuitMinimumCalls must not exceed the sliding window.");
        }
        if (circuitFailureRateThreshold <= 0 || circuitFailureRateThreshold > 100) {
            throw new IllegalArgumentException("circuitFailureRateThreshold must be between 0 and 100.");
        }
        requirePositive(circuitOpenDuration, "circuitOpenDuration");
        requirePositive(circuitHalfOpenCalls, "circuitHalfOpenCalls");
    }

    public int inquiryAdmissionCapacity() {
        return Math.addExact(inquiryMaxConcurrent, inquiryQueueCapacity);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
    }
}
