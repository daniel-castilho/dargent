package io.dargent.pspsimulator.webhook;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.dargent.pspsimulator.charge.Charge;

/**
 * Test double for {@link WebhookDispatcher}: records every dispatched charge so tests can assert
 * exactly-once semantics pre-chaos. Thread-safe because the real dispatcher will also deliver async.
 */
public class RecordingWebhookDispatcher implements WebhookDispatcher {

    private final List<Charge> delivered = new CopyOnWriteArrayList<>();

    @Override
    public void dispatch(Charge charge) {
        delivered.add(charge);
    }

    public int deliveryCount() {
        return delivered.size();
    }

    public List<Charge> delivered() {
        return List.copyOf(delivered);
    }

    public void clear() {
        delivered.clear();
    }
}