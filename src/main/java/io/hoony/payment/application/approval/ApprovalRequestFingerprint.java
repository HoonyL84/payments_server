package io.hoony.payment.application.approval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Creates a stable fingerprint for approval request payload comparison.
 */
public final class ApprovalRequestFingerprint {

    private ApprovalRequestFingerprint() {
    }

    /**
     * Creates a SHA-256 fingerprint from the business payload.
     */
    public static String from(ApprovePaymentCommand command) {
        String source = String.join(
                "|",
                command.userId(),
                command.merchantId(),
                command.orderId(),
                Long.toString(command.amount().minorUnits()),
                command.amount().currency()
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available.", exception);
        }
    }
}