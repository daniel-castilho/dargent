package io.dargent.payments.application;

/**
 * Same idempotency key replayed with a different request fingerprint
 * (E3 spec §5.1.3 row 3): maps to {@code 409 idempotency_key_conflict}.
 */
public final class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String message) {
        super(message);
    }
}
