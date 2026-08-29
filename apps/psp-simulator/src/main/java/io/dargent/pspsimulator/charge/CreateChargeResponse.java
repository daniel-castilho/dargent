package io.dargent.pspsimulator.charge;

import io.dargent.pspsimulator.config.PspProfile;

/**
 * 201 create response (E2 spec §5.1) — the charge plus the simulator profile fields the platform's
 * BR Code composer (E3) consumes.
 */
public record CreateChargeResponse(
        String txid,
        String status,
        long amount,
        String expiresAt,
        String callbackUrl,
        String description,
        String pixKey,
        String receiverName,
        String receiverCity) {

    public static CreateChargeResponse from(Charge charge, PspProfile profile) {
        return new CreateChargeResponse(
                charge.txid(),
                charge.status().name(),
                charge.amount(),
                charge.expiresAt().toString(),
                charge.callbackUrl(),
                charge.description(),
                profile.pixKey(),
                profile.receiverName(),
                profile.receiverCity());
    }
}