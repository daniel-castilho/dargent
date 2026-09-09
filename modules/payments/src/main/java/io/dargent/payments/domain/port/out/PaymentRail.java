package io.dargent.payments.domain.port.out;

import io.dargent.payments.domain.model.Txid;

/**
 * Payment strategy seam (M5 S0): the charge flavour the payments module depends on instead of the
 * PIX-named {@link PspPort}. A rail keeps the shared PSP contract (charge create + truth poll) and
 * owns its presentment — PIX returns the EMV BR Code computed from the receiver profile; a rail with
 * no presentment (card, M5 S1) returns {@code null}. Pure seam: no business rules, the lost-race and
 * money invariants live in the aggregate and the ports it already uses.
 */
public interface PaymentRail extends PspPort {

    /** Rail discriminator for metrics and routing (e.g. {@code "pix"}, {@code "card"}). */
    String rail();

    /**
     * The rail's charge presentment (what the end customer scans/pays with).
     *
     * @param amountCents amount in cents (never double/float)
     * @return presentment string, or {@code null} when the rail has none (card)
     */
    String presentment(Txid txid, long amountCents);
}
