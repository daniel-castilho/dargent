package io.dargent.payments.adapter.out.psp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PspPort;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * WireMock contract test for {@link SimulatorChargeAdapter} (E3 spec §5.7, §5.9, D19):
 * validates request/response byte-shape against E2 spec §5.1, retry policy (D19),
 * 409 read-back path, and exhaustion → 502.
 */
class SimulatorChargeAdapterWireMockIT {

    private WireMockServer wireMock;
    private SimulatorChargeAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        // Configure WireMock static client to use the correct admin port
        configureFor("localhost", wireMock.port());
        String baseUrl = "http://localhost:" + wireMock.port();
        adapter = new SimulatorChargeAdapter(baseUrl, 3, Duration.ofMillis(10), () -> 0L);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void happy_path_returns_charge_result_with_expected_fields() {
        String txid = "8KD4Z9X2Q7W1M5T3R6Y0A1B2C";
        String expiresAt = "2026-08-29T15:30:00Z";
        String endToEndId = "E2E-123";
        String brcode = "00020101021226530014BR.GOV.BCB.PIX0131...";

        stubFor(post(urlEqualTo("/cobs"))
                .withRequestBody(equalToJson("""
                        {
                          "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
                          "amount": 10000,
                          "expiresAt": "2026-08-29T15:00:00Z",
                          "callbackUrl": "https://example.com/callback",
                          "description": "Order #123"
                        }"""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
                                  "expiresAt": "2026-08-29T15:30:00Z",
                                  "endToEndId": "E2E-123",
                                  "brcode": "00020101021226530014BR.GOV.BCB.PIX0131..."
                                }""")));

        var input = new PspPort.CreateChargeInput(
                new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C"),
                10000,
                Instant.parse("2026-08-29T15:00:00Z"),
                "https://example.com/callback",
                "Order #123");

        var result = adapter.createCharge(input);

        assertThat(result.txid().value()).isEqualTo(txid);
        assertThat(result.expiresAt()).isEqualTo(Instant.parse(expiresAt));
        assertThat(result.endToEndId()).isEqualTo(endToEndId);
        assertThat(result.brcodePayload()).isEqualTo(brcode);
    }

    @Test
    void duplicate_txid_409_triggers_read_back_and_succeeds() {
        String txid = "8KD4Z9X2Q7W1M5T3R6Y0A1B2C";
        String expiresAt = "2026-08-29T15:30:00Z";

        // First POST returns 409
        stubFor(post(urlEqualTo("/cobs"))
                .willReturn(aResponse()
                        .withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"txid_already_exists\"}")));

        // GET /cobs/{txid} returns the existing charge
        stubFor(get(urlPathEqualTo("/cobs/" + txid))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
                                  "expiresAt": "2026-08-29T15:30:00Z",
                                  "endToEndId": "E2E-123",
                                  "brcode": "00020101021226530014BR.GOV.BCB.PIX..."
                                }""")));

        var input = new PspPort.CreateChargeInput(
                new Txid(txid),
                10000,
                Instant.parse("2026-08-29T15:00:00Z"),
                "https://example.com/callback",
                "Order #123");

        var result = adapter.createCharge(input);

        assertThat(result.txid().value()).isEqualTo(txid);
        assertThat(result.expiresAt()).isEqualTo(Instant.parse(expiresAt));
    }

    @Test
    void server_500_retries_up_to_max_attempts_then_throws() {
        AtomicInteger callCount = new AtomicInteger();

        // All POSTs return 500
        stubFor(post(urlEqualTo("/cobs"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"internal\"}")));

        var input = new PspPort.CreateChargeInput(
                new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C"),
                10000,
                Instant.parse("2026-08-29T15:00:00Z"),
                "https://example.com/callback",
                "Order #123");

        assertThatThrownBy(() -> adapter.createCharge(input))
                .isInstanceOf(SimulatorChargeAdapter.PspException.class)
                .hasMessageContaining("PSP call failed with status 500 after 3 attempts");
    }

    @Test
    void timeout_retries_up_to_max_attempts_then_throws() {
        AtomicInteger callCount = new AtomicInteger();

        // All POSTs delay longer than read timeout (5s)
        stubFor(post(urlEqualTo("/cobs"))
                .willReturn(aResponse()
                        .withFixedDelay(6000) // longer than 5s read timeout
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        var input = new PspPort.CreateChargeInput(
                new Txid("8KD4Z9X2Q7W1M5T3R6Y0A1B2C"),
                10000,
                Instant.parse("2026-08-29T15:00:00Z"),
                "https://example.com/callback",
                "Order #123");

        assertThatThrownBy(() -> adapter.createCharge(input))
                .isInstanceOf(SimulatorChargeAdapter.PspException.class)
                .hasMessageContaining("PSP call failed after 3 attempts");
    }
}