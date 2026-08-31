package io.dargent.ledger.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.dargent.shared.events.EventEnvelope;
import java.util.UUID;

/**
 * Strict Jackson 3 reader for the wire-format envelope (spec §5.3).
 * No lenient parsing, no fallback — invalid envelope = poison signal.
 */
public final class EventEnvelopeReader {

    private final ObjectMapper mapper;

    public EventEnvelopeReader() {
        this(new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    public EventEnvelopeReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parses the raw JSON body into an EventEnvelope.
     * Throws on any parsing error — caller must treat as poison.
     */
    public EventEnvelope read(String rawJson) {
        try {
            return mapper.readValue(rawJson, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid envelope JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the payment payload from a confirmed event.
     * Validates required fields and the fee+net=amount invariant.
     */
    public PaymentPayload extractPaymentPayload(EventEnvelope envelope) {
        if (!"payment.confirmed".equals(envelope.type())) {
            throw new IllegalArgumentException("Expected payment.confirmed, got " + envelope.type());
        }
        JsonNode p;
        try {
            p = new ObjectMapper().readTree(envelope.payloadJson());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid payload JSON: " + e.getMessage(), e);
        }

        long amount = p.path("amount").asLong();
        long fee = p.path("fee").asLong();
        long net = p.path("net").asLong();
        boolean late = p.path("late").asBoolean();

        if (fee + net != amount) {
            throw new IllegalArgumentException("Invariant violation: fee + net != amount (" + fee + " + " + net + " != " + amount + ")");
        }

        return new PaymentPayload(
                envelope.aggregateId(),
                envelope.merchantId().toString(),
                amount,
                fee,
                net,
                p.path("late").asBoolean()
        );
    }

    public record PaymentPayload(
            String txid,
            String merchantId,
            long amountCents,
            long feeCents,
            long netCents,
            boolean late
    ) {}
}