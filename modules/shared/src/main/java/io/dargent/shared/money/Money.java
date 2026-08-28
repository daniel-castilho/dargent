package io.dargent.shared.money;

/**
 * Immutable monetary amount in minor units (cents). BRL-only in v1 (design.md §4.2).
 * Never floating point anywhere — not in Java, not in JSON, not in SQL (AGENTS.md §3.1).
 */
public record Money(long cents, String currency) implements Comparable<Money> {

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("money cannot be negative: " + cents);
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        currency = currency.trim().toUpperCase();
    }

    public static Money of(long cents, String currency) {
        return new Money(cents, currency);
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(cents + other.cents, currency);
    }

    /** Throws when the result would be negative — amounts never go below zero (use signed ledger entries instead). */
    public Money minus(Money other) {
        requireSameCurrency(other);
        if (cents - other.cents < 0) {
            throw new IllegalArgumentException("subtraction would go negative: %d - %d".formatted(cents, other.cents));
        }
        return new Money(cents - other.cents, currency);
    }

    public boolean isZero() {
        return cents == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch: %s vs %s".formatted(currency, other.currency));
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(cents, other.cents);
    }
}
