package io.hoony.payment.presentation.payment;

import io.hoony.payment.domain.payment.PaymentState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class PaymentApprovalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void approve_요청이유효하면_승인결과를_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/payments/approve")
                        .header("Idempotency-Key", "controller-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user-1",
                                  "merchantId": "merchant-1",
                                  "orderId": "order-1",
                                  "amountMinorUnits": 30000,
                                  "currency": "KRW"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is(PaymentState.APPROVED.name())))
                .andExpect(jsonPath("$.reused", is(false)));
    }

    @Test
    void approve_멱등키헤더가없으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/payments/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "user-1",
                                  "merchantId": "merchant-1",
                                  "orderId": "order-missing-header",
                                  "amountMinorUnits": 30000,
                                  "currency": "KRW"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }
}