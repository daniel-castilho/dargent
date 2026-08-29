package io.dargent.pspsimulator.it;

import java.time.Duration;

import io.dargent.pspsimulator.charge.CreateChargeRequest;
import io.dargent.pspsimulator.charge.CreateChargeResponse;
import io.dargent.pspsimulator.charge.GetChargeResponse;
import io.dargent.pspsimulator.charge.PayChargeResponse;
import io.dargent.pspsimulator.webhook.TestWebhookReceiver;
import io.dargent.pspsimulator.webhook.WebhookSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The one full-stack lifecycle proof (spec §7): POST create → GET detail → pay → the real dispatcher
 * delivers ONE signed webhook to the stub receiver, whose captured bytes+timestamp recompute to the
 * captured signature — the exact procedure E4 will implement. Knobs all-off (M0 contract).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChargeLifecycleIT {

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
    void create_get_pay_lifecycle_delivers_one_signed_webhook_that_recomputes_per_spec_procedure() throws Exception {
        String txid = "LFXCTNCSBPCDP3EIW8UO9B4KF";
        String callbackUrl = "http://localhost:" + port + "/test-receiver/webhooks/psp";
        long amount = 12_345L;

        CreateChargeResponse created = client.post().uri("/cobs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateChargeRequest(txid, amount,
                        "2030-01-01T00:00:00Z", callbackUrl, "E2 lifecycle IT"))
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                    throw new AssertionError("create failed: " + response.getStatusCode());
                })
                .body(CreateChargeResponse.class);
        assertThat(created).isNotNull();
        assertThat(created.status()).isEqualTo("OPEN");
        assertThat(created.callbackUrl()).isEqualTo(callbackUrl);
        assertThat(created.pixKey()).isNotBlank();
        assertThat(created.receiverName()).isNotBlank();
        assertThat(created.receiverCity()).isNotBlank();

        PayChargeResponse paid = client.post().uri("/cobs/{txid}/payments", txid)
                .retrieve()
                .body(PayChargeResponse.class);
        assertThat(paid).isNotNull();
        assertThat(paid.status()).isEqualTo("PAID");
        String endToEndId = paid.endToEndId();
        assertThat(endToEndId).matches("^E[A-Za-z0-9]{31}$");
        assertThat(paid.paidAt()).isNotBlank();

        GetChargeResponse detail = client.get().uri("/cobs/{txid}", txid)
                .retrieve()
                .body(GetChargeResponse.class);
        assertThat(detail).isNotNull();
        assertThat(detail.status()).isEqualTo("PAID");
        assertThat(detail.endToEndId()).isEqualTo(endToEndId);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> receiver.captured().size() >= 1);
        await().during(Duration.ofMillis(350)).pollInterval(Duration.ofMillis(100))
                .atMost(Duration.ofSeconds(3))
                .until(() -> receiver.captured().size() == 1);

        TestWebhookReceiver.Captured captured = receiver.captured().get(0);
        assertThat(captured.contentType()).contains("application/json");
        assertThat(captured.signature()).isEqualTo(signer.sign(captured.timestamp(), captured.rawBody()));

        JsonNode json = mapper.readTree(captured.rawBody());
        assertThat(json.get("eventId").asText()).startsWith("psp-evt-");
        assertThat(json.get("type").asText()).isEqualTo("payment.confirmed");
        assertThat(json.get("txid").asText()).isEqualTo(txid);
        assertThat(json.get("endToEndId").asText()).isEqualTo(endToEndId);
        assertThat(json.get("amount").asLong()).isEqualTo(amount);
        assertThat(json.get("paidAt").asText()).isEqualTo(paid.paidAt());
    }
}