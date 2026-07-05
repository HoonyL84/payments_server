package io.hoony.payment.presentation.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("UP", "harness-payment-service", Instant.now());
    }

    public record HealthResponse(String status, String service, Instant checkedAt) {
    }
}