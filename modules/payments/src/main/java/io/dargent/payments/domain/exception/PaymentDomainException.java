package io.dargent.payments.domain.exception;

/**
 * Base of all payment domain errors. HTTP mapping is centralized elsewhere
 * (E3) — these carry the context a future 409 needs, nothing more.
 */
public abstract class PaymentDomainException extends RuntimeException {

    protected PaymentDomainException(String message) {
        super(message);
    }
}