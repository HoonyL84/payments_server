package io.hoony.mockpg;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/test-support")
public class MockPgTestSupportController {

    private final PgTransactionStore transactions;
    private final MockPgBehavior behavior;

    public MockPgTestSupportController(PgTransactionStore transactions, MockPgBehavior behavior) {
        this.transactions = transactions;
        this.behavior = behavior;
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        transactions.deleteAll();
        behavior.reset();
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/behavior")
    public ResponseEntity<Void> configure(@RequestBody MockPgBehavior.Configuration configuration) {
        behavior.configure(configuration);
        return ResponseEntity.noContent().build();
    }
}
