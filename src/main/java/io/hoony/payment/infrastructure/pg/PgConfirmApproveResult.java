package io.hoony.payment.infrastructure.pg;

public record PgConfirmApproveResult(PgConfirmApproveStatus status) {

    public static PgConfirmApproveResult approved() {
        return new PgConfirmApproveResult(PgConfirmApproveStatus.APPROVED);
    }

    public static PgConfirmApproveResult declined() {
        return new PgConfirmApproveResult(PgConfirmApproveStatus.DECLINED);
    }

    public static PgConfirmApproveResult unknown() {
        return new PgConfirmApproveResult(PgConfirmApproveStatus.UNKNOWN);
    }
}
