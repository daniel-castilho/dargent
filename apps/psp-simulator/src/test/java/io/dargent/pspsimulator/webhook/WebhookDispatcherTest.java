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
 * Wire-level proof of the async signed-delivery engine (E2 spec §5.4). The receiver recomputes the
 * signature from the captured raw bytes + timestamp — the exact procedure E4 will implement — proving
 * the dispatcher signed exactly the bytes it sent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookDispatcherTest {

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
    void webhook_delivery_carries_exact_signed_body_and_headers() throws Exception {
        String eventId = "psp-evt-3f2b9c1e-8a4d-4e2a-9b1c-7d5f0a6e8c9d";
        String endToEndId = "E9040381234567890123456789012345";
        Charge paid = new Charge("8KD4Z9X2Q7W1M5T3R6Y0A1B2C", 10_000,
                Instant.parse("2026-08-29T01:30:00Z"),
                "http://localhost:" + port + "/test-receiver/webhooks/psp", "Order #123");
        paid.pay(Instant.parse("2026-08-29T00:41:12Z"), endToEndId, eventId);

        dispatcher.dispatch(paid);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> receiver.captured().size() >= 1);
        TestWebhookReceiver.Captured captured = receiver.captured().get(0);

        assertThat(captured.contentType()).isEqualTo("application/json");
        assertThat(captured.rawBody()).isNotEmpty();
        assertThat(captured.timestamp()).isNotNull();
        assertThat(captured.signature()).isEqualTo(
                signer.sign(captured.timestamp(), captured.rawBody()));

        String body = new String(captured.rawBody(), StandardCharsets.UTF_8);
        JsonNode json = mapper.readTree(body);
        assertThat(json.get("eventId").asText()).isEqualTo(eventId);
        assertThat(json.get("type").asText()).isEqualTo("payment.confirmed");
        assertThat(json.get("txid").asText()).isEqualTo("8KD4Z9X2Q7W1M5T3R6Y0A1B2C");
        assertThat(json.get("endToEndId").asText()).isEqualTo(endToEndId);
        assertThat(json.get("amount").asLong()).isEqualTo(10_000L);
        assertThat(json.get("paidAt").asText()).isEqualTo("2026-08-29T00:41:12Z");
    }

    @Test
    void dispatch_is_single_attempt_by_default() {
        String eventId = "psp-evt-5d1a4b2c-9e8f-4a3b-8c7d-6e5f4a3b2c1d";
        String endToEndId = "E9040381234567890123456789012345";
        Charge paid = new Charge("2ML5A4Z9X2Q7W1M5T3R6Y0A1B2C", 5000,
                Instant.parse("2030-01-01T00:00:00Z"),
                "http://localhost:" + port + "/test-receiver/webhooks/psp", "one-shot");
        paid.pay(Instant.parse("2026-08-29T00:00:00Z"), endToEndId, eventId);

        dispatcher.dispatch(paid);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> receiver.captured().size() >= 1);
        await().during(Duration.ofMillis(500)).pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofSeconds(3))
                .until(() -> receiver.captured().size() == 1);
        assertThat(receiver.captured()).hasSize(1);
    }
}