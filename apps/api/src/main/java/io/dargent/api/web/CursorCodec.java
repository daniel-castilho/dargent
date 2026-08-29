package io.dargent.api.web;

import java.time.Instant;
import java.util.Base64;

/**
 * Opaque cursor codec for keyset pagination (E3 spec §5.3, §6.4).
 * Format: base64url("<txid>|<created_at_micros>") — stable under insertion.
 */
public final class CursorCodec {

    private CursorCodec() {}

    public static String encode(String txid, Instant createdAt) {
        String raw = txid + "|" + createdAt.toEpochMilli() * 1000; // micros
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    public static Decoded decode(String cursor) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor encoding", e);
        }
        String[] parts = decoded.split("\\|", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid cursor format");
        }
        String txid = parts[0];
        long micros;
        try {
            micros = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cursor timestamp", e);
        }
        return new Decoded(txid, Instant.ofEpochMilli(micros / 1000));
    }

    public record Decoded(String txid, Instant createdAt) {}
}