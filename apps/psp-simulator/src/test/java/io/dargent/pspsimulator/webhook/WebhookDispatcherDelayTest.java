package io.dargent.pspsimulator.webhook;

import java.time.Duration;
import java.time.Instant;

import io.dargent.pspsimulator.charge.Charge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Chaos knob `webhook-delay-ms` (spec §6): forced {@code =100} — the delivery is scheduled, so it
 * cannot be observed before the delay window elapses, and appears exactly once afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dargent.psp.chaos.webhook-delay-ms=100")
class WebhookDispatcherDelayTest {

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
    void delay_knob_holds_delivery_until_the_window_elapses_then_delivers_once() {
        Charge paid = new Charge("8TK2DZ9X2Q7W1M5T3R6Y0A1B2C", 9800,
                Instant.parse("2026-08-29T01:30:00Z"),
                "http://localhost:" + port + "/test-receiver/webhooks/psp", "late");
        paid.pay(Instant.parse("2026-08-29T00:00:00Z"),
                "E9040381234567890123456789012345",
                "psp-evt-1f2e3d4c-5b6a-4789-a1b2-c3d4e5f6a7b8");

        dispatcher.dispatch(paid);

        assertThat(receiver.captured()).isEmpty();

        await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(50))
                .until(() -> receiver.captured().size() >= 1);
        await().during(Duration.ofMillis(250)).pollInterval(Duration.ofMillis(50))
                .atMost(Duration.ofSeconds(3))
                .until(() -> receiver.captured().size() == 1);
        assertThat(receiver.captured()).hasSize(1);
    }
}