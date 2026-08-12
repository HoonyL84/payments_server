package io.hoony.mockpg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mock_pg_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "mock-pg.webhook.url=http://localhost:65535/api/v1/pg/webhooks"
})
@AutoConfigureMockMvc
class MockPgControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() throws Exception {
        mockMvc.perform(post("/internal/v1/test-support/reset"))
                .andExpect(status().isNoContent());
    }

    @Test
    void sameProviderRequestReturnsStoredTransaction() throws Exception {
        String body = approvalBody("pg-request-1", 10_000);

        mockMvc.perform(post("/api/v1/transactions/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.providerTransactionId", startsWith("mock-pg-")));

        mockMvc.perform(post("/api/v1/transactions/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/v1/transactions/requests/pg-request-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void reusedProviderRequestWithDifferentPayloadIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalBody("pg-request-conflict", 10_000)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/transactions/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalBody("pg-request-conflict", 20_000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROVIDER_REQUEST_CONFLICT"));
    }

    @Test
    void transactionPageReturnsCursor() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalBody("pg-page-1", 10_000)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/transactions/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalBody("pg-page-2", 20_000)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/transactions")
                        .param("from", "2020-01-01T00:00:00Z")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
    }
    private static String approvalBody(String providerRequestId, long amount) {
        return """
                {
                  "providerRequestId": "%s",
                  "paymentId": "%s",
                  "merchantId": "merchant-1",
                  "orderId": "order-1",
                  "amountMinorUnits": %d,
                  "currency": "KRW"
                }
                """.formatted(providerRequestId, UUID.randomUUID(), amount);
    }
}