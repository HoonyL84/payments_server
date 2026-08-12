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

    @PostMapping("/dataset")
    public ResponseEntity<Void> dataset(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100000") int size
    ) {
        if (size < 1 || size > 200_000) {
            throw new IllegalArgumentException("Dataset size must be between 1 and 200000.");
        }
        transactions.seedReconciliationDataset(size);
        return ResponseEntity.noContent().build();
    }
    @PutMapping("/behavior")
    public ResponseEntity<Void> configure(@RequestBody MockPgBehavior.Configuration configuration) {
        behavior.configure(configuration);
        return ResponseEntity.noContent().build();
    }
}