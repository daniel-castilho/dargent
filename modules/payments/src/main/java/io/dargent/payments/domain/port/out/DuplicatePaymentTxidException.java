package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.Txid;

/**
 * A {@code save} collided on the unique {@code txid} (decision D4). Adapter-agnostic
 * so the domain layer never leaks a persistence-specific exception; the caller
 * regenerates the txid and retries (E3).
 */
public final class DuplicatePaymentTxidException extends RuntimeException {

    private final Txid txid;

    public DuplicatePaymentTxidException(Txid txid) {
        super("duplicate payment txid: " + txid.value());
        this.txid = txid;
    }

    public Txid txid() {
        return txid;
    }
}