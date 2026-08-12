package io.hoony.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "reconciliation")
public record ReconciliationProperties(
        URI mockPgBaseUrl,
        int pageSize,
        int chunkSize,
        boolean datasetEnabled
) {
    public ReconciliationProperties {
        mockPgBaseUrl = mockPgBaseUrl == null ? URI.create("http://localhost:8090") : mockPgBaseUrl;
        pageSize = pageSize <= 0 ? 500 : Math.min(pageSize, 2_000);
        chunkSize = chunkSize <= 0 ? 500 : Math.min(chunkSize, 2_000);
    }
}