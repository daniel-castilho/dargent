package io.dargent.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * EventEnvelope compact-constructor contract (design.md §7.1): the envelope is broker-agnostic
 * and refuses to exist without its wire-mandatory fields. Shared carries zero business rules
 * (AGENTS §2.1) — these tests pin the STRUCTURAL contract only.
 */
class EventEnvelopeTest {

    private static final Instant AT = Instant.parse("2026-09-06T12:00:00Z");

    @Test
    void valid_envelope_is_constructed_with_all_wire_fields() {
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
                eventId, "payment.confirmed", 1, "TXID", UUID.randomUUID(), "req-1", AT, "{\"amount\":100}");
        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.type()).isEqualTo("payment.confirmed");
        assertThat(envelope.version()).isEqualTo(1);
        assertThat(envelope.aggregateId()).isEqualTo("TXID");
        assertThat(envelope.requestId()).isEqualTo("req-1");
        assertThat(envelope.occurredAt()).isEqualTo(AT);
        assertThat(envelope.payload()).isEqualTo("{\"amount\":100}");
    }

    @Test
    void null_wire_mandatory_field_is_rejected_by_name() {
        UUID id = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        assertThatThrownBy(() -> new EventEnvelope(null, "t", 1, "a", merchant, "r", AT, "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> new EventEnvelope(id, null, 1, "a", merchant, "r", AT, "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("type");
        assertThatThrownBy(() -> new EventEnvelope(id, "t", 1, null, merchant, "r", AT, "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("aggregateId");
        assertThatThrownBy(() -> new EventEnvelope(id, "t", 1, "a", merchant, "r", null, "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> new EventEnvelope(id, "t", 1, "a", merchant, "r", AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void version_below_one_is_rejected_requestId_and_merchant_may_be_null() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new EventEnvelope(id, "t", 0, "a", null, null, AT, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        // requestId and merchantId are optional on the wire (webhook-originated events carry no
        // request id; E7 settlement events carry no merchant) — null must be accepted.
        EventEnvelope envelope = new EventEnvelope(id, "settlement.journaled", 1, "a", null, null, AT, "{}");
        assertThat(envelope.requestId()).isNull();
        assertThat(envelope.merchantId()).isNull();
    }
}
