package io.dargent.payments.domain.model;

import io.dargent.shared.money.Money;
import java.time.Instant;

/**
 * Raised when a refund is created against a confirmed payment (spec §5.3).
 * Carries the proportional fee and net reversals for the ledger (D8) — refund
 * orchestration itself is E8.
 */
public record RefundCreated(
        Txid txid,
        Money refundAmount,
        Money feeReversal,
        Money netReversal,
        Instant occurredAt) implements PaymentEvent {
}