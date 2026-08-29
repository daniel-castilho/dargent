package io.dargent.payments.domain.exception;

import io.dargent.payments.domain.model.Txid;

/**
 * A refund would surpass the payment's remaining amount (design.md §6.3 →
 * {@code refund_exceeds_remaining}, 409). Carries remaining vs requested for
 * the future mapping; orchestration/ledger checks are E8.
 */
public final class RefundExceedsRemainingException extends PaymentDomainException {

    private final Txid txid;
    private final long remainingCents;
    private final long requestedCents;

    public RefundExceedsRemainingException(Txid txid, long remainingCents, long requestedCents) {
        super("refund of " + requestedCents + " cents exceeds the remaining " + remainingCents
                + " cents on payment " + txid.value());
        this.txid = txid;
        this.remainingCents = remainingCents;
        this.requestedCents = requestedCents;
    }

    public Txid txid() {
        return txid;
    }

    public long remainingCents() {
        return remainingCents;
    }

    public long requestedCents() {
        return requestedCents;
    }
}