package io.dargent.pspsimulator.webhook;

import java.time.Duration;
import java.time.Instant;

import io.dargent.pspsimulator.charge.Charge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Chaos knob `webhook-drop-rate` (spec §6): forced {@code =1.0} — every delivery is discarded, so
 * zero webhooks ever reach the receiver. Forced-mode extremes are deterministic and need no seed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dargent.psp.chaos.webhook-drop-rate=1.0")
class WebhookDispatcherDropTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebhookDispatcher dispatcher;

    @Autowired
    private TestWebhookReceiver receiver;

    @BeforeEach
    void clearReceiver() {
        receiver.clear();
    }

    @Test
    void drop_rate_1_discards_every_delivery() {
        Charge paid = new Charge("4QK1DZ9X2Q7W1M5T3R6Y0A1B2C", 3200,
                Instant.parse("2026-08-29T01:30:00Z"),
                "http://localhost:" + port + "/test-receiver/webhooks/psp", "dropped");
        paid.pay(Instant.parse("2026-08-29T00:00:00Z"),
                "E9040381234567890123456789012345",
                "psp-evt-d0f1e2d3-c4b5-4a6b-9c8d-7e6f5a4b3c2d");

        dispatcher.dispatch(paid);

        await().during(Duration.ofMillis(400)).pollInterval(Duration.ofMillis(50))
                .atMost(Duration.ofSeconds(4))
                .until(() -> receiver.captured().isEmpty());
        assertThat(receiver.captured()).isEmpty();
    }
}