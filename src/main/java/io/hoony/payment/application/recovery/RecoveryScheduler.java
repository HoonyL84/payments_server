package io.hoony.payment.application.recovery;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payments.recovery.scheduler-enabled", havingValue = "true")
public class RecoveryScheduler {
    private final RecoveryService recoveryService;
    private final AtomicBoolean running = new AtomicBoolean();

    public RecoveryScheduler(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(fixedDelayString = "${payments.recovery.fixed-delay:PT30S}")
    public void recover() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            recoveryService.recover(Duration.ofMinutes(1));
        } finally {
            running.set(false);
        }
    }
}
