package io.dargent.payments.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure domain validator for PSP webhook HMAC-SHA256 signatures (E4 spec §5.2).
 * No Spring, no Jackson — bytes in, verdict out. Injected {@link Clock} for anti-replay.
 *
 * <p>Scheme: HMAC-SHA256(secret, UTF-8(ts + "." + rawBody)), lowercase hex.
 * Verdict order:
 * <ol>
 *   <li>Timestamp parses as epoch-seconds? Else {@link Verdict#INVALID}</li>
 *   <li>{@code |now - ts| <= 300s}? Else {@link Verdict#EXPIRED}</li>
 *   <li>HMAC matches (constant-time {@link MessageDigest#isEqual})? Else {@link Verdict#INVALID}</li>
 *   <li>{@link Verdict#VALID}</li>
 * </ol>
 */
public final class WebhookSignatureValidator {

    private static final long REPLAY_WINDOW_SECONDS = 300L;
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String UTF8 = StandardCharsets.UTF_8.name();

    private final Clock clock;

    public WebhookSignatureValidator(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.clock = clock;
    }

    public Verdict verify(String timestamp, byte[] rawBody, String presentedSignature, String secret) {
        // 1. Timestamp must parse as epoch-seconds (Long)
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Verdict.INVALID;
        }

        // 2. Anti-replay window: |now - ts| <= 300s
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - ts) > REPLAY_WINDOW_SECONDS) {
            return Verdict.EXPIRED;
        }

        // 3. HMAC-SHA256(secret, UTF-8(ts + "." + rawBody)) lowercase hex
        String canonical = timestamp + "." + new String(rawBody, StandardCharsets.UTF_8);
        String expectedSignature;
        try {
            expectedSignature = hmacSha256Hex(secret, canonical);
        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            // Should never happen with valid secret/algorithm
            throw new IllegalStateException("HMAC computation failed", e);
        }

        // Constant-time compare
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.US_ASCII),
                presentedSignature.getBytes(StandardCharsets.US_ASCII))) {
            return Verdict.INVALID;
        }

        return Verdict.VALID;
    }

    static String hmacSha256Hex(String secret, String data)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** Package-visible helper for tests to compute the expected signature. */
    String computeSignatureForTest(String timestamp, String body, String secret)
            throws NoSuchAlgorithmException, InvalidKeyException {
        String canonical = timestamp + "." + body;
        return hmacSha256Hex(secret, canonical);
    }

    /**
     * Verification verdict.
     */
    public enum Verdict {
        VALID,
        EXPIRED,
        INVALID
    }
}