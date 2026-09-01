package io.dargent.notifications.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.time.Instant;

class EventEnvelopeReaderTest {

    private final EventEnvelopeReader reader = new EventEnvelopeReader();

    @Test
    void reads_valid_envelope() {
        String raw = """
                {
                  "eventId": "123e4567-e89b-12d3-a456-426614174000",
                  "type": "payment.confirmed",
                  "version": 1,
                  "aggregateId": "txid-123",
                  "merchantId": "11111111-1111-1111-1111-111111111111",
                  "requestId": "req-123",
                  "occurredAt": "2026-08-30T12:00:00Z",
                  "payload": { "txid": "txid-123", "amount": 10000 }
                }
                """;

        var envelopeParsed = reader.read(raw);

        assertThat(envelopeParsed.eventId()).isEqualTo(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        assertThat(envelopeParsed.type()).isEqualTo("payment.confirmed");
        assertThat(envelopeParsed.version()).isEqualTo(1);
        assertThat(envelopeParsed.aggregateId()).isEqualTo("txid-123");
        assertThat(envelopeParsed.merchantId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(envelopeParsed.requestId()).isEqualTo("req-123");
        assertThat(envelopeParsed.occurredAt()).isEqualTo(Instant.parse("2026-08-30T12:00:00Z"));
    }

    @Test
    void reads_envelope_with_null_requestId() {
        String raw = """
                {
                  "eventId": "123e4567-e89b-12d3-a456-426614174000",
                  "type": "payment.confirmed",
                  "version": 1,
                  "aggregateId": "txid-123",
                  "merchantId": "11111111-1111-1111-1111-111111111111",
                  "occurredAt": "2026-08-30T12:00:00Z",
                  "payload": {}
                }
                """;

        var envelopeParsed = reader.read(raw);

        assertThat(envelopeParsed.requestId()).isNull();
    }

    @Test
    void throws_on_invalid_json() {
        String raw = "not valid json";

        assertThatThrownBy(() -> reader.read(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid envelope JSON");
    }

    @Test
    void throws_on_missing_required_field() {
        String raw = """
                {
                    "eventId": "123e4567-e89b-12d3-a456-426614174000",
                    "type": "payment.confirmed"
                }
                """;

        assertThatThrownBy(() -> reader.read(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformed_occurredAt_is_poison_by_contract() {
        String raw = """
                {
                  "eventId": "123e4567-e89b-12d3-a456-426614174000",
                  "type": "payment.confirmed",
                  "version": 1,
                  "aggregateId": "txid-123",
                  "merchantId": "11111111-1111-1111-1111-111111111111",
                  "occurredAt": "not-a-date",
                  "payload": {}
                }
                """;

        assertThatThrownBy(() -> reader.read(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid occurredAt timestamp");
    }
}