package io.dargent.payments.domain.model;

import java.time.Instant;

/** Raised when an unpaid {@code PENDING} charge passes its deadline (spec §5.3). */
public record PaymentExpired(Txid txid, Instant occurredAt) implements PaymentEvent {
}