package io.dargent.pspsimulator.charge;

/**
 * Create-charge request (E2 spec §5.1). Amount is integer cents; expiry is RFC 3339 as the merchant
 * supplies it; callbackUrl is where the signed webhook will be delivered.
 */
public record CreateChargeRequest(
        String txid,
        long amount,
        String expiresAt,
        String callbackUrl,
        String description) {
}