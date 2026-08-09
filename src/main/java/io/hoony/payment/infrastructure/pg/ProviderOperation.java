package io.hoony.payment.infrastructure.pg;

public enum ProviderOperation {
    APPROVE("approve"),
    CANCEL("cancel"),
    CONFIRM_APPROVE("confirm_approve"),
    CONFIRM_CANCEL("confirm_cancel");

    private final String metricTag;

    ProviderOperation(String metricTag) {
        this.metricTag = metricTag;
    }

    public String metricTag() {
        return metricTag;
    }

    public boolean isInquiry() {
        return this == CONFIRM_APPROVE || this == CONFIRM_CANCEL;
    }
}
