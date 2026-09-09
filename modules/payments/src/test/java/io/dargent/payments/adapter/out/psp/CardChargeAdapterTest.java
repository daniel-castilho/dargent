package io.dargent.payments.adapter.out.psp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dargent.payments.adapter.out.psp.SimulatorChargeAdapter.PspException;
import io.dargent.payments.application.PspDeclinedException;
import io.dargent.payments.domain.model.Txid;
import io.dargent.payments.domain.port.out.PspPort.ChargeResult;
import io.dargent.payments.domain.port.out.PspPort.CobState;
import io.dargent.payments.domain.port.out.PspPort.CobStatus;
import io.dargent.payments.domain.port.out.PspPort.CreateChargeInput;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit contract for the card rail adapter (M5 S1): the instant profile answers 201 PAID at create
 * (charge result carries payer-bank ids and a null presentment), a {@code 402 card_declined} maps to
 * {@link PspDeclinedException}, and {@code getCob} serves the GET read-back. JDK {@link HttpServer}
 * stub — no Spring, no mocking of the outside world's neighbours.
 */
class CardChargeAdapterTest {

    private static final String PSP_EXPIRES_AT = "2030-01-01T00:00:00Z";
    private static final String PAID_AT = "2030-01-01T00:00:30Z";
    private static final long AMOUNT = 10_000L;

    private HttpServer server;
    private CardChargeAdapter adapter;
    private volatile Mode mode = Mode.APPROVE;
    private final AtomicReference<RequestProbe> probe = new AtomicReference<>();

    private enum Mode {
        APPROVE,
        DECLINE,
        NOT_FOUND
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/card-charges", this::handle);
        server.start();
        adapter = new CardChargeAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort(), 1, Duration.ofMillis(1), () -> 0L);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        boolean create = "POST".equals(exchange.getRequestMethod());
        String requestBody = create ? new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8) : "";
        probe.set(new RequestProbe(requestBody));
        String txid = create
                ? extractTxid(requestBody)
                : path.startsWith("/card-charges/") ? path.substring("/card-charges/".length()) : "";
        switch (mode) {
            case APPROVE -> {
                byte[] body = ("{\"txid\":\"" + txid + "\",\"status\":\"PAID\",\"amount\":" + AMOUNT
                                + ",\"expiresAt\":\"" + PSP_EXPIRES_AT + "\",\"endToEndId\":\"E9X\",\"paidAt\":\""
                                + PAID_AT + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(create ? 201 : 200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
            case DECLINE -> {
                byte[] body = "{\"code\":\"card_declined\",\"message\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(402, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
            default -> {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
            }
        }
    }

    private CreateChargeInput input(Txid txid) {
        return new CreateChargeInput(
                txid, 10_000, Instant.parse(PSP_EXPIRES_AT), "http://api:8080/w", "card order", "tok_1");
    }

    @Test
    void approve_maps_201_to_charge_result_with_null_presentment_and_echoes_expiry() {
        mode = Mode.APPROVE;
        Txid txid = new Txid("AAAABBBBCCCCDDDDEEEEFFFF1");

        ChargeResult result = adapter.createCharge(input(txid));

        assertThat(result.txid()).isEqualTo(txid);
        assertThat(result.expiresAt()).isEqualTo(Instant.parse(PSP_EXPIRES_AT)); // card echo, no expiry semantics
        assertThat(result.endToEndId()).isEqualTo("E9X");
        assertThat(result.brcodePayload()).isNull(); // no BR Code on the card rail
        assertThat(probe.get().body).contains("\"token\":\"tok_1\"");
        assertThat(probe.get().body).contains("\"amount\":10000");
    }

    @Test
    void decline_maps_402_to_psp_declined_exception() {
        mode = Mode.DECLINE;

        assertThatThrownBy(() -> adapter.createCharge(input(new Txid("AAAABBBBCCCCDDDDEEEEFFFF2"))))
                .isInstanceOf(PspDeclinedException.class)
                .hasMessage("card_declined");
    }

    @Test
    void get_cob_reads_the_approved_charge_as_paid_truth() {
        mode = Mode.APPROVE;
        Txid txid = new Txid("AAAABBBBCCCCDDDDEEEEFFFF3");

        CobStatus cob = adapter.getCob(txid);

        assertThat(cob.txid()).isEqualTo(txid);
        assertThat(cob.state()).isEqualTo(CobState.PAID);
        assertThat(cob.amountCents()).isEqualTo(10_000);
        assertThat(cob.endToEndId()).isEqualTo("E9X");
        assertThat(cob.paidAt()).isEqualTo(Instant.parse(PAID_AT));
    }

    @Test
    void get_cob_not_found_maps_to_psp_exception() {
        mode = Mode.NOT_FOUND;

        assertThatThrownBy(() -> adapter.getCob(new Txid("AAAABBBBCCCCDDDDEEEEFFFF4")))
                .isInstanceOf(PspException.class);
    }

    private static final class RequestProbe {
        final String body;

        RequestProbe(String body) {
            this.body = body;
        }
    }

    private static String extractTxid(String body) {
        int i = body.indexOf("\"txid\"");
        int start = body.indexOf("\"", i + 7) + 1;
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }
}
