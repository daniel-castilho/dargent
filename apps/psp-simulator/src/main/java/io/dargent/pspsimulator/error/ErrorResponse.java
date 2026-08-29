package io.dargent.pspsimulator.error;

/**
 * The simulator's error envelope (E2 spec §5.3): machines branch on {@code code}, humans read
 * {@code message}. The platform's anti-corruption layer maps this shape; no problem+json here.
 */
public record ErrorResponse(String code, String message) {
}