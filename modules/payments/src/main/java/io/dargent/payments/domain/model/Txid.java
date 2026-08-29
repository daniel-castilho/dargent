package io.dargent.payments.domain.model;

import java.util.Locale;

/**
 * PIX charge identifier (design.md §4.2, decision D4): public id, exactly 25
 * alphanumeric characters — the Bacen cap. App-generated, unique-constrained,
 * used as the SNS ordering key (MessageGroupId).
 *
 * <p>Input is normalized to uppercase at construction; any shape outside
 * {@code [A-Z0-9]{25}} is unrepresentable.
 */
public record Txid(String value) {

    private static final String PATTERN = "[A-Z0-9]{25}";

    public Txid {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("txid is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches(PATTERN)) {
            throw new IllegalArgumentException(
                    "txid must be exactly 25 alphanumeric characters, got: " + normalized);
        }
        value = normalized;
    }
}