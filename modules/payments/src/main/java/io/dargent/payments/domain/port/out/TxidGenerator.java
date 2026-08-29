package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.Txid;

/** Generates valid {@link Txid} values; collision retry is the caller's contract (D4). */
public interface TxidGenerator {

    Txid generate();
}