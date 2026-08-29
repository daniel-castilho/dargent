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
 * Endpoint-driven proof that {@code webhook-delay-ms} holds a delivery taken via the real payment path
 * (spec §7 + §6): after pay the receiver is quiet, and the single delivery only appears once the
 * window has elapsed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dargent.psp.chaos.webhook-delay-ms=150")
class ChaosPaymentDelayIT {

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
    void paying_via_http_holds_the_delivery_until_the_delay_window_elapses() {
        String txid = "KNYAQGEC1EIO5CQT535KOM58F";
        String callbackUrl = "http://localhost:" + port + "/test-receiver/webhooks/psp";

        client.post().uri("/cobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateChargeRequest(txid, 8100, "2030-01-01T00:00:00Z", callbackUrl, "late"))
                .retrieve()
                .toBodilessEntity();
        client.post().uri("/cobs/{txid}/payments", txid)
                .retrieve()
                .toBodilessEntity();

        assertThat(receiver.captured()).isEmpty();

        await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(50))
                .until(() -> receiver.captured().size() >= 1);
        await().during(Duration.ofMillis(250)).pollInterval(Duration.ofMillis(50))
                .atMost(Duration.ofSeconds(3))
                .until(() -> receiver.captured().size() == 1);
        assertThat(receiver.captured()).hasSize(1);
    }
}