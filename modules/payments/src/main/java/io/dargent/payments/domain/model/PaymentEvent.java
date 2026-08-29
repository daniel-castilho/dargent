package io.dargent.payments.domain.model;

/**
 * Marker for entity-raised payment events (spec §5.3). Mapping to the
 * {@code EventEnvelope}/outbox is E6 — these records stay serialization-free.
 */
public interface PaymentEvent {
}