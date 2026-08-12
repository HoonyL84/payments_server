package io.hoony.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/reconciliation/datasets")
@ConditionalOnProperty(name = "reconciliation.dataset-enabled", havingValue = "true")
class ReconciliationDatasetController {

    private final ReconciliationDatasetSeeder seeder;

    ReconciliationDatasetController(ReconciliationDatasetSeeder seeder) {
        this.seeder = seeder;
    }

    @PostMapping
    ReconciliationDatasetSeeder.DatasetResult seed(@RequestParam(defaultValue = "100000") int size) {
        return seeder.seed(size);
    }
}