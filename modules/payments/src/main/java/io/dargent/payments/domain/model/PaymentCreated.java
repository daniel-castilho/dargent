package io.dargent.payments.domain.model;

import io.dargent.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * Raised when a charge is born {@code PENDING} (spec §5.3). {@code expiresAt}
 * is copied from the PSP — never computed locally (design.md §4.2).
 */
public record PaymentCreated(
        Txid txid,
        UUID merchantId,
        Money amount,
        String description,
        Instant expiresAt,
        Instant occurredAt) implements PaymentEvent {
}