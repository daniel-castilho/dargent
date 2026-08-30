package io.dargent.payments.application;

/**
 * Idempotency key is currently {@code IN_FLIGHT} (concurrent create or a crashed
 * in-flight request). Maps to {@code 425 idempotency_key_in_flight} + {@code Retry-After: 1}
 * (E3 spec §5.1.3 row 4, D18).
 */
public final class IdempotencyKeyInFlightException extends RuntimeException {

    public IdempotencyKeyInFlightException(String message) {
        super(message);
    }
}
