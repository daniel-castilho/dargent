package io.dargent.ledger.application;

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
    void extracts_payment_payload_valid() {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                UUID.randomUUID(),
                "payment.confirmed",
                1,
                "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "req-123",
                Instant.now(),
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}"
        );

        var payload = reader.extractPaymentPayload(envelope);

        assertThat(payload.txid()).isEqualTo("txid-123");
        assertThat(payload.merchantId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(payload.amountCents()).isEqualTo(10000L);
        assertThat(payload.feeCents()).isEqualTo(100L);
        assertThat(payload.netCents()).isEqualTo(9900L);
        assertThat(payload.late()).isFalse();
    }

    @Test
    void throws_on_wrong_event_type() {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                UUID.randomUUID(),
                "payment.created",
                1,
                "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                null,
                Instant.now(),
                "{}"
        );

        assertThatThrownBy(() -> reader.extractPaymentPayload(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected payment.confirmed");
    }

    @Test
    void throws_on_invalid_payload_json() {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                UUID.randomUUID(),
                "payment.confirmed",
                1,
                "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                null,
                Instant.now(),
                "not valid json"
        );

        assertThatThrownBy(() -> reader.extractPaymentPayload(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid payload JSON");
    }

    @Test
    void throws_on_fee_net_amount_invariant_violation() {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                UUID.randomUUID(),
                "payment.confirmed",
                1,
                "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                null,
                Instant.now(),
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":200,\"net\":9900,\"late\":false}"
        );

        assertThatThrownBy(() -> reader.extractPaymentPayload(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invariant violation: fee + net != amount");
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