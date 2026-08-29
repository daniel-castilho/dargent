package io.dargent.pspsimulator.webhook;

import io.dargent.pspsimulator.charge.Charge;
import org.springframework.stereotype.Component;

/**
 * Temporary no-op so the context wires while the async signed-delivery engine is still landing
 * (S5). Replaced by {@code AsyncWebhookDispatcher} in the same step that adds it — never shipped.
 */
@Component
public class NoopWebhookDispatcher implements WebhookDispatcher {

    @Override
    public void dispatch(Charge charge) {
        // no-op placeholder — the real engine arrives in S5
    }
}