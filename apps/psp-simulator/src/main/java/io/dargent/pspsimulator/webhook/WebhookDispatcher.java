package io.dargent.pspsimulator.webhook;

import io.dargent.pspsimulator.charge.Charge;

/**
 * The dispatch hook the payer bank calls after a successful payment (E2 spec §5.3 → §5.4). The
 * production implementation is the async signed-delivery engine (spec §5.4, S5); tests substitute a
 * recording fake. The charge already carries everything the engine needs: callbackUrl, txid, the
 * stable eventId, endToEndId, amount and paidAt.
 */
public interface WebhookDispatcher {

    void dispatch(Charge charge);
}