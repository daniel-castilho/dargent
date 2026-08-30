package io.dargent.payments.application;

/**
 * The PSP create phase exhausted its retries (D19) after the transactional core
 * committed. Maps to {@code 502 psp_unavailable}; the payment is left {@code FAILED}
 * with the idempotency key row deleted (no snapshot).
 */
public final class PspUnavailableException extends RuntimeException {

    public PspUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public PspUnavailableException(String message) {
        super(message);
    }
}
