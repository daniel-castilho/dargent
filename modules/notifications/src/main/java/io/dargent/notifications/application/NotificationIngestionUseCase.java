package io.dargent.notifications.application;

import io.dargent.notifications.domain.port.out.NotificationStore;
import io.dargent.shared.events.EventEnvelope;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Notification ingestion use case (E10 spec §3–§4).
 * At-least-once + local dedupe by event_id; every event type records.
 * No transaction beyond the single idempotent insert.
 */
public final class NotificationIngestionUseCase {

    private final EventEnvelopeReader reader;
    private final NotificationStore store;
    private final JdbcClient jdbc;
    private final Clock clock;

    public NotificationIngestionUseCase(EventEnvelopeReader reader, NotificationStore store,
            JdbcClient jdbc, Clock clock) {
        this.reader = reader;
        this.store = store;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Processes one SQS message (runOnce semantics).
     * Returns true if message was processed (ack), false if poison (nack).
     *
     * <p>The terminal status is decided before the single idempotent insert so the event row carries
     * its true state from birth: every event type records. A single insert avoids the silent
     * ON CONFLICT no-op that would otherwise freeze the row.</p>
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
        boolean inserted = store.insertNotificationIfAbsent(
                envelope.eventId(), envelope.type(), envelope.aggregateId(),
                envelope.merchantId(), envelope.payload(), envelope.occurredAt());

        // Duplicate delivery — ack + skip (idempotent)
        return true;
    }
}