package io.dargent.pspsimulator.charge;

import java.time.Instant;

/**
 * 200 detail response (E2 spec §5.2) — the charge's truth, the reconciler's endpoint in E5.
 * {@code endToEndId}/{@code paidAt} are null until the charge is paid; {@code status} is computed
 * against {@code now} (EXPIRED for unpaid past-expiry, PAID permanent).
 */
public record GetChargeResponse(
        String txid,
        String status,
        long amount,
        String expiresAt,
        String endToEndId,
        String paidAt) {

    public static GetChargeResponse from(Charge charge, Instant now) {
        return new GetChargeResponse(
                charge.txid(),
                charge.statusFor(now).name(),
                charge.amount(),
                charge.expiresAt().toString(),
                charge.endToEndId(),
                charge.paidAt() == null ? null : charge.paidAt().toString());
    }
}