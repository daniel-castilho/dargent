package io.dargent.notifications.application;

import io.dargent.notifications.domain.port.out.NotificationStore;
import io.dargent.shared.events.EventEnvelope;

/**
 * Notification ingestion use case (E10 spec §3–§4).
 * At-least-once + local dedupe by event_id; every event type records.
 * No transaction beyond the single idempotent insert.
 * Read → single idempotent insert → ack; poison → false.
 */
public final class NotificationIngestionUseCase {

    private final EventEnvelopeReader reader;
    private final NotificationStore store;

    public NotificationIngestionUseCase(EventEnvelopeReader reader, NotificationStore store) {
        this.reader = reader;
        this.store = store;
    }

    /**
     * Processes one SQS message (runOnce semantics).
     * Returns true if message was processed (ack), false if poison (nack).
     *
     * <p>Reads envelope, single idempotent insert; duplicate (event_id exists) → ack + skip.</p>
     */
    public boolean processMessage(String rawBody) {
        EventEnvelope envelope;
        try {
            envelope = reader.read(rawBody);
        } catch (IllegalArgumentException e) {
            // Poison: invalid JSON or envelope structure — do NOT ack
            return false;
        }

        // Single idempotent insert; a duplicate (event_id already present) is an at-least-once retry.
        store.insertNotificationIfAbsent(
                envelope.eventId(), envelope.type(), envelope.aggregateId(),
                envelope.merchantId(), envelope.payload(), envelope.occurredAt());

        // Duplicate delivery — ack + skip (idempotent)
        return true;
    }
}