package io.dargent.payments.domain.model;

/**
 * Canonical payment states (design.md §4.1, decision D11).
 * Transitions are guarded inside the {@code Payment} entity and re-imposed by conditional
 * UPDATEs at the persistence seam — the database arbitrates races (AGENTS.md §3.2).
 */
public enum PaymentStatus {
    PENDING,
    CONFIRMED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    EXPIRED,
    FAILED;

    /** EXPIRED is deliberately NOT terminal (D6): a late confirmation resurrects the payment. */
    public boolean isTerminal() {
        return this == REFUNDED || this == FAILED;
    }
}
