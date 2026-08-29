package io.dargent.pspsimulator.charge;

/**
 * 200 payer-bank response (E2 spec §5.3) after a successful payment.
 */
public record PayChargeResponse(String txid, String status, String endToEndId, String paidAt) {

    public static PayChargeResponse from(Charge charge) {
        return new PayChargeResponse(charge.txid(), charge.status().name(),
                charge.endToEndId(), charge.paidAt().toString());
    }
}