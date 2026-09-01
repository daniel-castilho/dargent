package io.dargent.notifications.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.dargent.shared.events.EventEnvelope;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.UUID;

/**
 * Strict Jackson 3 reader for the wire-format envelope (E10 spec §3).
 * No lenient parsing, no fallback — invalid envelope = poison signal.
 * Mirrors ledger's EventEnvelopeReader as of e946a15 (post-BD-16).
 */
public final class EventEnvelopeReader {

    private final ObjectMapper mapper;

    public EventEnvelopeReader() {
        this(new ObjectMapper());
    }

    public EventEnvelopeReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parses the raw JSON body into an EventEnvelope per design.md §7.1 wire contract: {@code payload}
     * is an object whose JSON text becomes {@link EventEnvelope#payload()}. Binding is done here at the
     * boundary (Jackson belongs to adapters, AGENTS.md §2.2) so shared stays Jackson-free.
     * Throws on any parsing error or missing required field — caller must treat as poison.
     */
    public EventEnvelope read(String rawJson) {
        JsonNode node;
        try {
            node = mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid envelope JSON: " + e.getMessage(), e);
        }
        try {
            return new EventEnvelope(
                    UUID.fromString(required(node, "eventId").asText()),
                    required(node, "type").asText(),
                    required(node, "version").asInt(),
                    required(node, "aggregateId").asText(),
                    UUID.fromString(required(node, "merchantId").asText()),
                    node.path("requestId").asText(null),
                    parseInstant(required(node, "occurredAt").asText()),
                    required(node, "payload").toString()
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid envelope: " + e.getMessage(), e);
        }
    }

    private Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid occurredAt timestamp: " + text, e);
        }
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            throw new IllegalArgumentException("missing required field '" + field + "'");
        }
        return value;
    }
}