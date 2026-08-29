package io.dargent.payments.domain.exception;

import io.dargent.payments.domain.model.PaymentStatus;
import io.dargent.payments.domain.model.Txid;

/**
 * An illegal state transition was attempted on a payment (design.md §6.3 →
 * {@code invalid_transition}, 409). Carries from/to/txid for the future mapping.
 */
public final class InvalidTransitionException extends PaymentDomainException {

    private final Txid txid;
    private final PaymentStatus from;
    private final PaymentStatus to;

    public InvalidTransitionException(Txid txid, PaymentStatus from, PaymentStatus to) {
        super("illegal payment transition: " + txid.value() + " cannot move " + from + " → " + to);
        this.txid = txid;
        this.from = from;
        this.to = to;
    }

    public Txid txid() {
        return txid;
    }

    public PaymentStatus from() {
        return from;
    }

    public PaymentStatus to() {
        return to;
    }
}