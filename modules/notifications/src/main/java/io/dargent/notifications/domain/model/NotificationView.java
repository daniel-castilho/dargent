package io.dargent.notifications.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side projection of a stored notification (E10 spec §7).
 * Deliberately omits {@code payload} (the list endpoint stays lean); txid is nullable
 * (schema §2.1 allows NULL) and merchant is never part of the response — it is the
 * authenticated tenant (AGENTS §3.7).
 */
public record NotificationView(
        UUID id,
        UUID eventId,
        String type,
        String txid,
        Instant occurredAt,
        Instant createdAt) {
}
