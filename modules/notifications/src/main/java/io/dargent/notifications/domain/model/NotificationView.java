package io.dargent.notifications.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side projection of a stored notification (E10 spec §7).
 * Deliberately omits {@code payload} (the list endpoint stays lean); txid is nullable
 * (schema §2.1 allows NULL). {@code merchantId} is an OUTPUT echo of the authenticated
 * tenant (TD-17 ruling: the input-only ban on AGENTS §3.7 forbids merchant from
 * query/path/body, not from the response); it is never used to scope the query — the
 * {@code WHERE merchant_id = ?} comes from the principal in the adapter/controller.
 */
public record NotificationView(
        UUID id,
        UUID eventId,
        String type,
        String txid,
        UUID merchantId,
        Instant occurredAt,
        Instant createdAt) {
}
