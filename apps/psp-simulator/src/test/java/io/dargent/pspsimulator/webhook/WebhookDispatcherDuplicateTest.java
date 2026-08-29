package io.dargent.pspsimulator.webhook;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

import io.dargent.pspsimulator.charge.Charge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Chaos knob `webhook-duplicate` (spec §6): forced {@code =true} — the SAME event (same eventId) is
 * delivered exactly twice, each delivery validly signed. Order of the two deliveries is contractually
 * free; only the count and the shared eventId are asserted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dargent.psp.chaos.webhook-duplicate=true")
class WebhookDispatcherDuplicateTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebhookDispatcher dispatcher;

    @Autowired
    private TestWebhookReceiver receiver;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private WebhookSigner signer;

    @BeforeEach
    void clearReceiver() {
        receiver.clear();
    }

    @Test
    void duplicate_knob_delivers_the_same_event_twice_with_valid_signatures() throws Exception {
        String eventId = "psp-evt-9a1b2c3d-4e5f-4a6b-8c7d-1e2f3a4b5c6d";
        String endToEndId = "E9040381234567890123456789012345";
        Charge paid = new Charge("1XK4DZ9X2Q7W1M5T3R6Y0A1B2C", 7500,
                Instant.parse("2026-08-29T01:30:00Z"),
                "http://localhost:" + port + "/test-receiver/webhooks/psp", "twice");
        paid.pay(Instant.parse("2026-08-29T00:00:00Z"), endToEndId, eventId);

        dispatcher.dispatch(paid);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> receiver.captured().size() >= 2);
        await().during(Duration.ofMillis(300)).pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofSeconds(3))
                .until(() -> receiver.captured().size() == 2);

        String firstEventId = null;
        for (TestWebhookReceiver.Captured captured : receiver.captured()) {
            assertThat(captured.signature()).isEqualTo(signer.sign(captured.timestamp(), captured.rawBody()));
            assertThat(captured.contentType()).isEqualTo("application/json");
            JsonNode json = mapper.readTree(captured.rawBody());
            assertThat(json.get("eventId").asText()).isEqualTo(eventId);
            assertThat(json.get("type").asText()).isEqualTo("payment.confirmed");
            assertThat(json.get("endToEndId").asText()).isEqualTo(endToEndId);
            if (firstEventId == null) {
                firstEventId = json.get("eventId").asText();
            }
            assertThat(json.get("eventId").asText()).isEqualTo(firstEventId);
        }
        assertThat(receiver.captured()).hasSize(2);
    }
}