package io.hoony.payment.presentation.recovery;

import io.hoony.payment.application.recovery.RecoveryReport;
import io.hoony.payment.application.recovery.RecoveryRunResult;
import io.hoony.payment.application.recovery.RecoveryService;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/recovery")
public class RecoveryController {
    private final RecoveryService recoveryService;

    public RecoveryController(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @GetMapping("/report")
    public RecoveryReport report(@RequestParam(defaultValue = "60") long staleSeconds) {
        return recoveryService.inspect(Duration.ofSeconds(staleSeconds));
    }

    @PostMapping("/run")
    public RecoveryRunResult recover(@RequestParam(defaultValue = "60") long staleSeconds) {
        return recoveryService.recover(Duration.ofSeconds(staleSeconds));
    }
}
