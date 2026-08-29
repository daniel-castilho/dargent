package io.dargent.pspsimulator.webhook;

import io.dargent.pspsimulator.charge.Charge;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The webhook event JSON object (E2 spec §5.4) — the exact field set the platform will parse.
 * Serialized once to bytes by the dispatcher; those bytes are what gets signed and sent (no
 * re-serialization, no pretty-printing).
 */
public record WebhookEvent(
        String eventId,
        String type,
        String txid,
        String endToEndId,
        long amount,
        String paidAt) {

    public static WebhookEvent of(Charge charge) {
        if (charge.endToEndId() == null || charge.paidAt() == null || charge.eventId() == null) {
            throw new IllegalStateException("Cannot dispatch webhook for an unpaid charge " + charge.txid());
        }
        return new WebhookEvent(charge.eventId(), "payment.confirmed", charge.txid(),
                charge.endToEndId(), charge.amount(), charge.paidAt().toString());
    }

    public byte[] toJsonBytes(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsBytes(this);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize webhook event for txid " + txid, e);
        }
    }
}