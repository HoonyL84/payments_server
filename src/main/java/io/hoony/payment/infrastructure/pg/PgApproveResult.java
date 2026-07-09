package io.hoony.payment.infrastructure.pg;

public record PgApproveResult(PgApproveStatus status) {

    public static PgApproveResult approved() {
        return new PgApproveResult(PgApproveStatus.APPROVED);
    }

    public static PgApproveResult declined() {
        return new PgApproveResult(PgApproveStatus.DECLINED);
    }
}