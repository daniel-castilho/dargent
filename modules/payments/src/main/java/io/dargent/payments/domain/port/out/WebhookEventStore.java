package io.dargent.payments.domain.port.out;

import java.util.Optional;
import java.util.UUID;

/** Port for webhook event storage (E4 spec §5.4) — raw evidence first, processing second. */
public interface WebhookEventStore {

    /**
     * Tries to insert a new webhook event as RECEIVED.
     * Returns empty if inserted; returns existing record if duplicate `provider_event_id`
     * (caller must handle the duplicate — either ignore or reprocess).
     */
    Optional<WebhookEventRecord> insertIfAbsent(WebhookEventRecord record);

    /** Marks the event as PROCESSED (called after successful confirmation). */
    void markProcessed(String providerEventId);

    /** Marks the event as IGNORED (e.g., unknown type, unknown txid, amount mismatch). */
    void markIgnored(String providerEventId);

    /** Finds an event by its provider_event_id. */
    Optional<WebhookEventRecord> findByProviderEventId(String providerEventId);
}