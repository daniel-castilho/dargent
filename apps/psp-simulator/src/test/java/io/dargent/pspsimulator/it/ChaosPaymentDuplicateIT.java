package io.dargent.pspsimulator.it;

import java.time.Duration;

import io.dargent.pspsimulator.charge.CreateChargeRequest;
import io.dargent.pspsimulator.charge.PayChargeResponse;
import io.dargent.pspsimulator.webhook.TestWebhookReceiver;
import io.dargent.pspsimulator.webhook.WebhookSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Endpoint-driven proof that the {@code webhook-duplicate} chaos knob surfaces through the real
 * payment path (spec §7 + §6): pay via HTTP, and the SAME eventId arrives exactly twice, each
 * delivery validly signed. Asserts counts, never order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dargent.psp.chaos.webhook-duplicate=true")
class ChaosPaymentDuplicateIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestWebhookReceiver receiver;

    @Autowired
    private WebhookSigner signer;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        receiver.clear();
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void paying_via_http_duplicates_the_delivered_event_with_the_same_stable_event_id() throws Exception {
        String txid = "EL3GUXNTCCHYPLVQK2ZIUS50K";
        String callbackUrl = "http://localhost:" + port + "/test-receiver/webhooks/psp";

        client.post().uri("/cobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateChargeRequest(txid, 5600, "2030-01-01T00:00:00Z", callbackUrl, "dup"))
                .retrieve()
                .toBodilessEntity();
        PayChargeResponse paid = client.post().uri("/cobs/{txid}/payments", txid)
                .retrieve()
                .body(PayChargeResponse.class);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> receiver.captured().size() >= 2);
        await().during(Duration.ofMillis(300)).pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofSeconds(3))
                .until(() -> receiver.captured().size() == 2);

        String firstEventId = null;
        for (TestWebhookReceiver.Captured captured : receiver.captured()) {
            assertThat(captured.signature()).isEqualTo(signer.sign(captured.timestamp(), captured.rawBody()));
            JsonNode json = mapper.readTree(captured.rawBody());
            assertThat(json.get("type").asText()).isEqualTo("payment.confirmed");
            assertThat(json.get("txid").asText()).isEqualTo(txid);
            assertThat(json.get("endToEndId").asText()).isEqualTo(paid.endToEndId());
            if (firstEventId == null) {
                firstEventId = json.get("eventId").asText();
            }
            assertThat(json.get("eventId").asText()).isEqualTo(firstEventId);
        }
    }
}