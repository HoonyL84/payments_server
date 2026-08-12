package io.hoony.payment.infrastructure.pg;

import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.domain.common.ResourceConflictException;
import io.hoony.payment.domain.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpPaymentGatewayTest {

    private MockRestServiceServer server;
    private HttpPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://mock-pg");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new HttpPaymentGateway(builder.build());
    }

    @Test
    void mapsApprovedHttpResponse() {
        server.expect(requestTo("http://mock-pg/api/v1/transactions/approve"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "providerRequestId": "approve-1",
                          "operation": "APPROVE",
                          "status": "APPROVED",
                          "providerTransactionId": "pg-txn-1"
                        }
                        """, MediaType.APPLICATION_JSON));

        PaymentGateway.ApprovalResult result = gateway.approve(new PaymentGateway.ApprovalRequest(
                UUID.randomUUID(),
                "merchant-1",
                "order-1",
                new Money(10_000, "KRW"),
                "MOCK",
                "default",
                "approve-1"
        ));

        assertThat(result.status()).isEqualTo(PaymentGateway.ApprovalStatus.APPROVED);
        assertThat(result.providerTransactionId()).isEqualTo("pg-txn-1");
        server.verify();
    }

    @Test
    void propagatesProviderRequestPayloadConflict() {
        server.expect(requestTo("http://mock-pg/api/v1/transactions/approve"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> gateway.approve(new PaymentGateway.ApprovalRequest(
                UUID.randomUUID(),
                "merchant-1",
                "order-1",
                new Money(10_000, "KRW"),
                "MOCK",
                "default",
                "approve-conflict"
        ))).isInstanceOf(ResourceConflictException.class);
        server.verify();
    }

    @Test
    void mapsMissingStatusQueryToUnknown() {
        server.expect(requestTo("http://mock-pg/api/v1/transactions/requests/approve-missing"))
                .andRespond(withResourceNotFound());

        PaymentGateway.ConfirmationResult result = gateway.confirmApprove(
                new PaymentGateway.ConfirmationRequest(
                        UUID.randomUUID(),
                        "MOCK",
                        "default",
                        "approve-missing"
                )
        );

        assertThat(result.status()).isEqualTo(PaymentGateway.ConfirmationStatus.UNKNOWN);
        server.verify();
    }
}