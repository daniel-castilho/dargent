package io.dargent.payments.application;

/**
 * The PSP declined the charge at create (card rail: HTTP 402 {@code card_declined} from the
 * simulator profile). The create use case mirrors the exhaustion path — the payment is left
 * {@code FAILED} with reason {@code card_declined} (no journal lies), the idempotency key is
 * deleted so a retry can re-attempt, and the exception is re-thrown so the HTTP layer answers
 * {@code 402 card_declined}.
 */
public final class PspDeclinedException extends RuntimeException {

    public PspDeclinedException(String message, Throwable cause) {
        super(message, cause);
    }

    public PspDeclinedException(String message) {
        super(message);
    }
}
