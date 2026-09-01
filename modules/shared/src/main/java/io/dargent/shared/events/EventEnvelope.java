package io.dargent.shared.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Broker-agnostic event envelope (design.md §7.1) — ours, not the broker's.
 * {@code payload} carries the serialized payload JSON (per the §7.1 wire contract {@code "payload"}
 * object); JSON binding belongs to adapters at the edge, so no Jackson types leak into
 * shared/domain code (AGENTS.md §2.2, coding-standards §8).
 */
public record EventEnvelope(
        UUID eventId,
        String type,
        int version,
        String aggregateId,
        UUID merchantId,
        String requestId,
        Instant occurredAt,
        String payload
) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(type, "type is required");
        Objects.requireNonNull(aggregateId, "aggregateId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(payload, "payload is required");
        if (version < 1) {
            throw new IllegalArgumentException("event version must be >= 1: " + version);
        }
    }
}
