package io.dargent.pspsimulator.it;

import java.time.Duration;

import io.dargent.pspsimulator.charge.CreateChargeRequest;
import io.dargent.pspsimulator.webhook.TestWebhookReceiver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Endpoint-driven proof that {@code webhook-drop-rate} squashes deliveries taken via the real payment
 * path (spec §7 + §6): pay succeeds (200), yet no webhook ever reaches the receiver.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dargent.psp.chaos.webhook-drop-rate=1.0")
class ChaosPaymentDropIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestWebhookReceiver receiver;

    private RestClient client;

    @BeforeEach
    void setUp() {
        receiver.clear();
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void paying_via_http_with_drop_rate_1_produces_zero_deliveries() {
        String txid = "9EP1FRBY1U5LZVUQ48RXCZX93";
        String callbackUrl = "http://localhost:" + port + "/test-receiver/webhooks/psp";

        client.post().uri("/cobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateChargeRequest(txid, 4200, "2030-01-01T00:00:00Z", callbackUrl, "drop"))
                .retrieve()
                .toBodilessEntity();

        Integer status = client.post().uri("/cobs/{txid}/payments", txid)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value();
        assertThat(status).isEqualTo(200);

        await().during(Duration.ofMillis(400)).pollInterval(Duration.ofMillis(50))
                .atMost(Duration.ofSeconds(4))
                .until(() -> receiver.captured().isEmpty());
        assertThat(receiver.captured()).isEmpty();
    }
}