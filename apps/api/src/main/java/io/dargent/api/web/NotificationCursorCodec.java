package io.dargent.api.web;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque cursor codec for notification keyset pagination (E10 spec §7).
 * Format: base64url("<created_at_micros>|<id>") over (created_at desc, id desc).
 */
public final class NotificationCursorCodec {

    private NotificationCursorCodec() {}

    public static String encode(Instant createdAt, UUID id) {
        long micros = createdAt.toEpochMilli() * 1000;
        String raw = micros + "|" + id;
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
        long micros;
        UUID id;
        try {
            micros = Long.parseLong(parts[0]);
            id = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor value", e);
        }
        return new Decoded(micros, id);
    }

    public record Decoded(long createdAtMicros, UUID id) {}
}
