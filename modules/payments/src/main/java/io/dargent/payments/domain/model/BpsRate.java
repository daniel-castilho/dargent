package io.dargent.payments.domain.model;

/**
 * Percentage fee rate in basis points (design.md §4.2, decision D5).
 * One hundred bps equals one percent; valid range is {@code [0, 10 000]}.
 */
public record BpsRate(int value) {

    public BpsRate {
        if (value < 0 || value > 10_000) {
            throw new IllegalArgumentException("bps must be between 0 and 10 000, got: " + value);
        }
    }
}