package io.dargent.pspsimulator.card;

import io.dargent.pspsimulator.charge.Charge;

/**
 * 201 response for an approved card charge (M5 S1). No BR Code, no PIX profile fields — the charge
 * is born {@code PAID} and carries the payer-bank ids the platform's confirmation needs.
 */
public record CardChargeResponse(String txid, String status, long amount, String endToEndId, String paidAt) {

    public static CardChargeResponse from(Charge charge) {
        if (charge.endToEndId() == null || charge.paidAt() == null) {
            throw new IllegalStateException("Cannot respond for an unpaid card charge " + charge.txid());
        }
        return new CardChargeResponse(
                charge.txid(),
                charge.status().name(),
                charge.amount(),
                charge.endToEndId(),
                charge.paidAt().toString());
    }
}
