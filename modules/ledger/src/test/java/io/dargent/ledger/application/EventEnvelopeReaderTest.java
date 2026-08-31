package io.dargent.ledger.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dargent.shared.events.EventEnvelope;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

class EventEnvelopeReaderTest {

    private final EventEnvelopeReader reader = new EventEnvelopeReader();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Test
    void reads_valid_envelope() throws Exception {
        var envelope = new EventEnvelope(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "payment.confirmed",
                1,
                "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "req-123",
                java.time.Instant.parse("2026-08-30T12:00:00Z"),
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"description\":\"Test\",\"expiresAt\":\"2026-08-30T12:30:00Z\"}"
        );
        String raw = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsString(envelope);

        var envelopeParsed = reader.read(raw);

        assertThat(envelopeParsed.eventId()).isEqualTo(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        assertThat(envelopeParsed.type()).isEqualTo("payment.confirmed");
        assertThat(envelopeParsed.version()).isEqualTo(1);
        assertThat(envelopeParsed.aggregateId()).isEqualTo("txid-123");
        assertThat(envelopeParsed.merchantId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(envelopeParsed.requestId()).isEqualTo("req-123");
        assertThat(envelopeParsed.occurredAt()).isEqualTo("2026-08-30T12:00:00Z");
    }

    @Test
    void reads_envelope_with_null_requestId() throws Exception {
        var envelope = new io.dargent.shared.events.EventEnvelope(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "payment.confirmed",
                1,
                "txid-123",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                null,
                java.time.Instant.parse("2026-08-30T12:00:00Z"),
                "{}"
        );
        String raw = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsString(envelope);

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
                java.time.Instant.now(),
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":100,\"net\":9900,\"late\":false}"
        );

        var payload = new EventEnvelopeReader(new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())).extractPaymentPayload(envelope);

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
                java.time.Instant.now(),
                "{}"
        );

        var reader2 = new EventEnvelopeReader(new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));

        assertThatThrownBy(() -> reader2.extractPaymentPayload(envelope))
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
                java.time.Instant.now(),
                "not valid json"
        );

        var reader2 = new EventEnvelopeReader(new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));

        assertThatThrownBy(() -> reader2.extractPaymentPayload(envelope))
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
                java.time.Instant.now(),
                "{\"txid\":\"txid-123\",\"merchantId\":\"11111111-1111-1111-1111-111111111111\",\"amount\":10000,\"fee\":200,\"net\":9900,\"late\":false}"
        );

        var reader2 = new EventEnvelopeReader(new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));

        assertThatThrownBy(() -> reader2.extractPaymentPayload(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invariant violation: fee + net != amount");
    }
}