package io.dargent.payments.domain.model;

/**
 * Network-wide PIX identifier, PSP-generated and immutable (design.md §4.2).
 * Shape-only validation — {@code E + 31 alphanumeric} (32 total); the composition
 * internals are owned by the PSP and treated as opaque. Doubles as the webhook
 * dedupe input key ({@code endToEndId + type}).
 */
public record EndToEndId(String value) {

    private static final String PATTERN = "E[A-Za-z0-9]{31}";

    public EndToEndId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("endToEndId is required");
        }
        if (!value.matches(PATTERN)) {
            throw new IllegalArgumentException(
                    "endToEndId must be E followed by 31 alphanumeric characters, got: " + value);
        }
    }
}