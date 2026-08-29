package io.dargent.pspsimulator.charge;

/**
 * Cob lifecycle (E2 spec §5.2/§5.3). {@code EXPIRED} is always computed, never stored — a paid
 * charge is PAID forever regardless of time.
 */
public enum ChargeStatus {
    OPEN,
    PAID,
    EXPIRED
}