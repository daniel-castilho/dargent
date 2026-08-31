package io.dargent.payments.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the full E3 §5.6 event envelope that the outbox {@code payload} column must carry
 * (owner decision, Dargent E6): {@code {eventId, type, version, aggregateId, merchantId,
 * requestId, occurredAt, payload}}. Without the top-level {@code eventId} every real outbox row
 * would hit the relay's defect path (stuck PENDING); the relay derives
 * {@code MessageDeduplicationId = eventId} from it. Key order is deterministic (LinkedHashMap →
 * Jackson) so the stored jsonb text doubles as the wire format (E6 §5.3). The envelope
 * {@code eventId} stays UUID v4 (E6 §5.5).
 */
public final class EventEnvelopeFactory {

    private final EventSerializer serializer;

    public EventEnvelopeFactory(EventSerializer serializer) {
        this.serializer = serializer;
    }

    /**
     * Serializes the full envelope around a domain payload map.
     *
     * @param type       event type, e.g. {@code payment.created}
     * @param version    event version (≥ 1)
     * @param aggregateId the txid
     * @param merchantId owner
     * @param requestId  correlation id (null for PSP callbacks — webhook has none)
     * @param payload    domain payload (nested object)
     * @param occurredAt event time
     */
    public String envelope(String type, int version, String aggregateId, UUID merchantId,
            String requestId, Map<String, Object> payload, Instant occurredAt) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("type", type);
        envelope.put("version", version);
        envelope.put("aggregateId", aggregateId);
        envelope.put("merchantId", merchantId.toString());
        envelope.put("requestId", requestId);
        envelope.put("occurredAt", occurredAt.toString());
        envelope.put("payload", payload);
        return serializer.serialize(envelope);
    }
}