package io.dargent.payments.application;

import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic Jackson-3 serialization for outbox event payloads (BD-8, lesson #13).
 * The outbox {@code payload} jsonb column is serialized exactly once, through this
 * mapper, in a fixed {@link Map} key order — never via {@code String.format} JSON.
 * Lives in {@code application/} (not shared; see DEV-R2-1) and uses the module's
 * existing {@code tools.jackson} compile dependency.
 */
public final class EventSerializer {

    private final ObjectMapper mapper;

    public EventSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EventSerializer() {
        this(JsonMapper.builder().build());
    }

    /** Serializes a {@link Map} (insertion-ordered → deterministic keys) to JSON. */
    public String serialize(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
