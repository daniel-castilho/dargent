package io.dargent.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stripe-style API key hashing (E3 spec §3.2, §5.9): keys are stored as SHA-256 hex with an
 * indexable prefix. The raw key is never persisted or logged. Constant-time comparison prevents
 * timing attacks on the hash.
 *
 * Format: {@code psp_test_<43 base62 chars>} — prefix is {@code psp_test_} (11 chars),
 * the rest is high-entropy random. The prefix allows O(1) DB lookup before hash comparison.
 */
public final class ApiKeyHasher {

    private static final String ALGORITHM = "SHA-256";
    private static final String PREFIX = "psp_test_";
    private static final int PREFIX_LENGTH = PREFIX.length();

    private ApiKeyHasher() {}

    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String prefix(String rawKey) {
        if (rawKey == null || rawKey.length() < PREFIX_LENGTH) {
            throw new IllegalArgumentException("key too short for prefix extraction");
        }
        return rawKey.substring(0, PREFIX_LENGTH);
    }

    public static boolean constantTimeEquals(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null || expectedHex.length() != actualHex.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < expectedHex.length(); i++) {
            result |= expectedHex.charAt(i) ^ actualHex.charAt(i);
        }
        return result == 0;
    }

    public static String generateRawKey() {
        byte[] entropy = new byte[32];
        new java.security.SecureRandom().nextBytes(entropy);
        return PREFIX + Base62.encode(entropy);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Minimal Base62 encoder for the key suffix (no external deps). */
    private static final class Base62 {
        private static final char[] ALPHABET =
                "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

        private Base62() {}

        static String encode(byte[] input) {
            // Convert bytes to a large integer, then encode in base62
            // For 32 bytes, this produces ~43 chars (log_62(2^256) ≈ 43.2)
            java.math.BigInteger num = new java.math.BigInteger(1, input);
            StringBuilder sb = new StringBuilder();
            java.math.BigInteger base = java.math.BigInteger.valueOf(62);
            while (num.compareTo(java.math.BigInteger.ZERO) > 0) {
                java.math.BigInteger[] divMod = num.divideAndRemainder(base);
                sb.append(ALPHABET[divMod[1].intValue()]);
                num = divMod[0];
            }
            // Pad to 43 chars if needed
            while (sb.length() < 43) {
                sb.append('0');
            }
            return sb.reverse().toString();
        }
    }
}