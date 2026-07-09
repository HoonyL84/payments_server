package io.hoony.payment.application.port.out;

import io.hoony.payment.infrastructure.pg.PgApproveRequest;
import io.hoony.payment.infrastructure.pg.PgApproveResult;

/**
 * Port for external PG approval calls.
 */
public interface PaymentGateway {

    /**
     * Calls PG approval.
     */
    PgApproveResult approve(PgApproveRequest request);
}