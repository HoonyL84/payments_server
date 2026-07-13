package io.hoony.payment.application.cancellation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CancellationRequestFingerprint {

    private CancellationRequestFingerprint() {
    }

    public static String from(CancelPaymentCommand command) {
        String source = String.join(
                "|",
                command.paymentId().toString(),
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