package io.dargent.payments.adapter.out.psp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PspPort.CobState;
import io.dargent.payments.domain.port.out.PspPort.CobStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Contract IT for {@link SimulatorChargeAdapter#getCob} pinning the REAL simulator wire body
 * verbatim (E2 spec §5.2 / {@code GetChargeResponse}: {@code status} + {@code amount}). TD-32:
 * the pre-fix parser read {@code state}/{@code amountCents} — fields the simulator never emits —
 * so the reconciler silently ladder-advanced on a null state. If the simulator ever changes this
 * shape, THIS test fails first. Substantive field names must full-match the published simulator.
 */
class PspGetCobContractIT {

    private static final String TXID = "8KD4Z9X2Q7W1M5T3R6Y0A1B2C";

    /** Exact body the psp-simulator emits for a paid charge (GetChargeResponse). */
    private static final String REAL_PAID_BODY = """
            {
              "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
              "status": "PAID",
              "amount": 10000,
              "expiresAt": "2026-08-29T15:30:00Z",
              "endToEndId": "E2E-123",
              "paidAt": "2026-08-29T15:31:00Z"
            }""";

    /** Exact body the psp-simulator emits for an unpaid charge (null ids; JSON nulls included). */
    private static final String REAL_OPEN_BODY = """
            {
              "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
              "status": "OPEN",
              "amount": 10000,
              "expiresAt": "2026-08-29T15:30:00Z",
              "endToEndId": null,
              "paidAt": null
            }""";

    private WireMockServer wireMock;
    private SimulatorChargeAdapter adapter;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        configureFor("localhost", wireMock.port());
        String baseUrl = "http://localhost:" + wireMock.port();
        adapter = new SimulatorChargeAdapter(baseUrl, 3, Duration.ofMillis(10), () -> 0L);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void get_cob_maps_real_simulator_wire_paid() {
        stubFor(get(urlPathEqualTo("/cobs/" + TXID))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(REAL_PAID_BODY)));

        CobStatus cob = adapter.getCob(new Txid(TXID));

        assertThat(cob.txid().value()).isEqualTo(TXID);
        assertThat(cob.state()).isEqualTo(CobState.PAID);
        assertThat(cob.amountCents()).isEqualTo(10000);
        assertThat(cob.expiresAt()).isEqualTo(Instant.parse("2026-08-29T15:30:00Z"));
        assertThat(cob.endToEndId()).isEqualTo("E2E-123");
        assertThat(cob.paidAt()).isEqualTo(Instant.parse("2026-08-29T15:31:00Z"));
    }

    @Test
    void get_cob_maps_real_simulator_wire_open_with_null_paid_at() {
        stubFor(get(urlPathEqualTo("/cobs/" + TXID))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(REAL_OPEN_BODY)));

        CobStatus cob = adapter.getCob(new Txid(TXID));

        assertThat(cob.txid().value()).isEqualTo(TXID);
        assertThat(cob.state()).isEqualTo(CobState.OPEN);
        assertThat(cob.amountCents()).isEqualTo(10000);
        assertThat(cob.paidAt()).isNull();
    }
}