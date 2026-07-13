package io.hoony.payment.presentation.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hoony.payment.application.port.out.PaymentGateway;
import io.hoony.payment.domain.cancellation.CancellationState;
import io.hoony.payment.domain.payment.PaymentState;
import io.hoony.payment.infrastructure.pg.FakePaymentGateway;
import io.hoony.payment.infrastructure.pg.PgApproveStatus;
import io.hoony.payment.infrastructure.pg.PgConfirmApproveStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class PaymentApprovalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FakePaymentGateway paymentGateway;

    @Test
    void approve_요청이유효하면_승인결과를_반환한다() throws Exception {
        mockMvc.perform(approvalRequest("controller-key-1", "order-controller-1", 30_000))
                .andExpect(status().isOk())
                .andExpect(header().string(PaymentApprovalController.IDEMPOTENCY_REPLAYED_HEADER, "false"))
                .andExpect(jsonPath("$.state", is(PaymentState.APPROVED.name())))
                .andExpect(jsonPath("$.reused").doesNotExist());
    }

    @Test
    void approve_같은멱등요청은_같은응답본문을_재사용한다() throws Exception {
        MvcResult first = mockMvc.perform(approvalRequest(
                        "controller-replay-key-1",
                        "order-controller-replay-1",
                        30_000
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(PaymentApprovalController.IDEMPOTENCY_REPLAYED_HEADER, "false"))
                .andReturn();

        mockMvc.perform(approvalRequest(
                        "controller-replay-key-1",
                        "order-controller-replay-1",
                        30_000
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(PaymentApprovalController.IDEMPOTENCY_REPLAYED_HEADER, "true"))
                .andExpect(content().json(first.getResponse().getContentAsString()));
    }

    @Test
    void approve_멱등키payload충돌은_409를_반환한다() throws Exception {
        mockMvc.perform(approvalRequest(
                        "controller-conflict-key-1",
                        "order-controller-conflict-1",
                        30_000
                ))
                .andExpect(status().isOk());

        mockMvc.perform(approvalRequest(
                        "controller-conflict-key-1",
                        "order-controller-conflict-1",
                        40_000
                ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RESOURCE_CONFLICT")));
    }

    @Test
    void approve_다른멱등키로_같은주문을요청하면_409를_반환한다() throws Exception {
        mockMvc.perform(approvalRequest(
                        "controller-order-key-1",
                        "order-controller-conflict-2",
                        30_000
                ))
                .andExpect(status().isOk());

        mockMvc.perform(approvalRequest(
                        "controller-order-key-2",
                        "order-controller-conflict-2",
                        30_000
                ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RESOURCE_CONFLICT")));
    }

    @Test
    void approve_멱등키헤더가없으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/payments/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalBody("order-missing-header", 30_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void payment_현재상태를_조회한다() throws Exception {
        MvcResult approval = mockMvc.perform(approvalRequest(
                        "controller-query-key-1",
                        "order-controller-query-1",
                        30_000
                ))
                .andExpect(status().isOk())
                .andReturn();
        String paymentId = responseJson(approval).get("paymentId").asText();

        mockMvc.perform(get("/api/v1/payments/{paymentId}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId", is(paymentId)))
                .andExpect(jsonPath("$.state", is(PaymentState.APPROVED.name())));
    }

    @Test
    void payment_존재하지않으면_404를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{paymentId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));
    }

    @Test
    void confirm_내부경로에서_pending결제를_최종상태로_수렴시킨다() throws Exception {
        paymentGateway.nextApproveStatus(PgApproveStatus.TIMED_OUT);
        try {
            MvcResult approval = mockMvc.perform(approvalRequest(
                            "controller-confirm-key-1",
                            "order-controller-confirm-1",
                            30_000
                    ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state", is(PaymentState.PENDING_CONFIRMATION.name())))
                    .andReturn();
            String paymentId = responseJson(approval).get("paymentId").asText();

            paymentGateway.nextConfirmApproveStatus(PgConfirmApproveStatus.APPROVED);
            mockMvc.perform(post("/internal/v1/payments/{paymentId}/confirm", paymentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state", is(PaymentState.APPROVED.name())));

            mockMvc.perform(get("/api/v1/payments/{paymentId}", paymentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state", is(PaymentState.APPROVED.name())));
        } finally {
            paymentGateway.nextApproveStatus(PgApproveStatus.APPROVED);
            paymentGateway.nextConfirmApproveStatus(PgConfirmApproveStatus.APPROVED);
        }
    }

    @Test
    void confirm_pending이아닌결제는_409를_반환한다() throws Exception {
        MvcResult approval = mockMvc.perform(approvalRequest(
                        "controller-confirm-key-2",
                        "order-controller-confirm-2",
                        30_000
                ))
                .andExpect(status().isOk())
                .andReturn();
        String paymentId = responseJson(approval).get("paymentId").asText();

        mockMvc.perform(post("/internal/v1/payments/{paymentId}/confirm", paymentId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RESOURCE_CONFLICT")));
    }

    @Test
    void cancel_요청이유효하면_취소결과를반환하고_재요청은응답을재사용한다() throws Exception {
        MvcResult approval = mockMvc.perform(approvalRequest(
                        "controller-cancel-approval-key-1",
                        "order-controller-cancel-1",
                        30_000
                ))
                .andExpect(status().isOk())
                .andReturn();
        String paymentId = responseJson(approval).get("paymentId").asText();

        MvcResult first = mockMvc.perform(cancellationRequest(
                        paymentId,
                        "controller-cancel-key-1",
                        10_000
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(PaymentApprovalController.IDEMPOTENCY_REPLAYED_HEADER, "false"))
                .andExpect(jsonPath("$.state", is(CancellationState.CANCELED.name())))
                .andReturn();

        mockMvc.perform(cancellationRequest(paymentId, "controller-cancel-key-1", 10_000))
                .andExpect(status().isOk())
                .andExpect(header().string(PaymentApprovalController.IDEMPOTENCY_REPLAYED_HEADER, "true"))
                .andExpect(content().json(first.getResponse().getContentAsString()));
    }

    @Test
    void cancel_timeout은_confirm으로최종상태에수렴한다() throws Exception {
        MvcResult approval = mockMvc.perform(approvalRequest(
                        "controller-cancel-approval-key-2",
                        "order-controller-cancel-2",
                        30_000
                ))
                .andExpect(status().isOk())
                .andReturn();
        String paymentId = responseJson(approval).get("paymentId").asText();

        paymentGateway.nextCancellationStatus(PaymentGateway.CancellationStatus.TIMED_OUT);
        try {
            MvcResult cancellation = mockMvc.perform(cancellationRequest(
                            paymentId,
                            "controller-cancel-key-2",
                            10_000
                    ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(
                            "$.state",
                            is(CancellationState.CANCEL_PENDING_CONFIRMATION.name())
                    ))
                    .andReturn();
            String cancellationId = responseJson(cancellation).get("cancellationId").asText();

            mockMvc.perform(post(
                            "/internal/v1/payments/{paymentId}/cancellations/{cancellationId}/confirm",
                            paymentId,
                            cancellationId
                    ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state", is(CancellationState.CANCELED.name())));
        } finally {
            paymentGateway.nextCancellationStatus(PaymentGateway.CancellationStatus.CANCELED);
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder cancellationRequest(
            String paymentId,
            String idempotencyKey,
            long amount
    ) {
        return post("/api/v1/payments/{paymentId}/cancel", paymentId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "amountMinorUnits": %d,
                          "currency": "KRW"
                        }
                        """.formatted(amount));
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder approvalRequest(
            String idempotencyKey,
            String orderId,
            long amount
    ) {
        return post("/api/v1/payments/approve")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvalBody(orderId, amount));
    }

    private String approvalBody(String orderId, long amount) {
        return """
                {
                  "userId": "user-1",
                  "merchantId": "merchant-1",
                  "orderId": "%s",
                  "amountMinorUnits": %d,
                  "currency": "KRW"
                }
                """.formatted(orderId, amount);
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
