package io.dargent.payments.domain.model;

import io.dargent.shared.money.Money;
import java.time.Instant;

/**
 * Raised when a payment confirms — normally, or as a resurrection of an expired
 * charge ({@code late=true}, decision D6). Carries the fee breakdown so consumers
 * never recompute it (D7).
 */
public record PaymentConfirmed(
        Txid txid,
        EndToEndId endToEndId,
        Money amount,
        Money fee,
        Money net,
        boolean late,
        Instant occurredAt) implements PaymentEvent {
}