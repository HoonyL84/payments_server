package io.hoony.payment.presentation.testsupport;

import io.hoony.payment.infrastructure.fault.FailurePoint;
import io.hoony.payment.infrastructure.fault.FaultInjectionState;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("k6")
@RestController
@RequestMapping("/internal/v1/test-support/fault")
public class FaultInjectionController {
    private final FaultInjectionState state;

    public FaultInjectionController(FaultInjectionState state) {
        this.state = state;
    }

    @PostMapping
    public ResponseEntity<Void> arm(@RequestParam FailurePoint point) {
        state.arm(point);
        return ResponseEntity.noContent().build();
    }
}
