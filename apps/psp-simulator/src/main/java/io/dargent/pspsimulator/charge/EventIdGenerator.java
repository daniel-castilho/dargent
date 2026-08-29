package io.dargent.pspsimulator.charge;

import java.util.UUID;

/**
 * Webhook event identifier: {@code psp-evt-<uuid4>} (E2 spec §5.4). Stable per payment — the
 * duplicate knob re-delivers the same eventId for the same charge.
 */
public final class EventIdGenerator {

    public String generate() {
        return "psp-evt-" + UUID.randomUUID();
    }
}