package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.api.web.RequestIdFilter;
import io.dargent.payments.application.ReconciliationUseCase;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Card rail ITs (M5 S1, D2 adjudication): full context, real PostgreSQL + Flyway, real security,
 * real JDK {@link HttpClient}. The {@code /card-charges} PSP profile is a JDK {@link HttpServer}
 * stub (approve at create = born PAID, magic-amount decline = 402). The webhook intake is the real
 * one — confirmation is delivered through the genuine HMAC-signed intake, proving card rides the
 * SAME confirm/journal path as PIX (zero domain edits). Covers: approve+webhook confirm with single
 * journal, decline 402 (FAILED, no journal, key deleted), replay no-double-journal, and the
 * reconciler confirming an approved card charge via {@code GET /card-charges/{txid}}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {DargentApiApplication.class, CardPaymentIT.CardTestConfig.class},
        properties = {
            "dargent.psp.webhook-secret=dev-only-secret", "dargent.relay.enabled=false",
            "DARGENT_RECONCILER_ENABLED=true", "DARGENT_RECONCILER_SCAN_MS=3600000"
        })
@Testcontainers
class CardPaymentIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
    private static final long FIXED_NOW_SECS = FIXED_CLOCK.instant().getEpochSecond();
    private static final String PSP_EXPIRES_AT = "2026-08-29T12:02:00Z";
    private static final String PAID_AT = "2026-08-29T12:00:30Z";
    private static final String SECRET = "dev-only-secret";
    private static final String ENDPOINT = "POST /v1/payments";
    private static final String TYPE = "payment.confirmed";
    private static final String E2E = "E9" + "0".repeat(29) + "A"; // fixed by the stub for the whole IT
    private static final long AMOUNT = 10_000L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CardStub card;

    @Autowired
    ReconciliationUseCase reconciler;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                        + "payments.audit_log, payments.payments, payments.api_keys restart identity cascade")
                .update();
        jdbc.sql("insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
        card.mode = CardStub.Mode.APPROVE;
    }

    // -------------------------------------------------------------- happy path

    @Test
    void card_approve_is_pending_rail_card_and_confirm_via_webhook_leaves_one_journal() throws Exception {
        card.mode = CardStub.Mode.APPROVE;
        String idemKey = "idem-card-happy-01";
        String reqId = "req-card-happy-01";

        var resp = post(
                "/v1/payments",
                body("{\"amount\":10000,\"description\":\"Card #1\",\"method\":\"card\",\"cardToken\":\"tok_1\"}"),
                authHeaders(idemKey, reqId));

        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(resp.headers().firstValue(RequestIdFilter.HEADER)).contains(reqId);
        var json = parse(resp);
        String txid = json.at("/txid").asText();
        assertThat(txid).isNotBlank();
        assertThat(json.at("/status").asText()).isEqualTo("PENDING");
        assertThat(json.at("/amount").asLong()).isEqualTo(10000);
        // FINDING-S1-2: card presentment is explicit null — never a PIX BR Code
        assertThat(json.at("/brcode").isNull()).isTrue();

        // the rail discriminator is persisted with the payment
        assertThat(railOf(txid)).isEqualTo("card");
        // idempotency snapshot holds the explicit-null brcode
        String snapshot = jdbc.sql(
                        "select response_body::text from payments.idempotency_keys where merchant_id=:m and idempotency_key=:k and endpoint=:e")
                .param("m", MERCHANT)
                .param("k", idemKey)
                .param("e", ENDPOINT)
                .query(String.class)
                .single();
        assertThat(snapshot).contains("\"brcode\": null");

        // exactly one payment.created so far; no failed/confirmed lies
        assertThat(outboxTypes(txid)).containsExactly("payment.created");

        // confirm through the REAL signed webhook intake (same path PIX uses)
        var wh = sendWebhook(String.valueOf(FIXED_NOW_SECS), confirmedBody(txid, E2E, 10000));
        assertThat(wh.statusCode()).isEqualTo(200);
        assertThat(parse(wh).at("/status").asText()).isEqualTo("processed");

        assertThat(paymentStatus(txid)).isEqualTo("CONFIRMED");
        assertThat(paymentE2E(txid)).isEqualTo(E2E);
        assertThat(paymentFee(txid)).isEqualTo(100L); // fee 100 bps on the card rail too
        assertThat(paymentNet(txid)).isEqualTo(9900L);
        assertThat(jdbc.sql("select status from payments.webhook_events where provider_event_id = :p")
                        .param("p", E2E + "|" + TYPE)
                        .query(String.class)
                        .single())
                .isEqualTo("PROCESSED");
        // single journal: exactly one payment.created + exactly one payment.confirmed
        assertThat(outboxTypes(txid)).containsExactlyInAnyOrder("payment.created", "payment.confirmed");
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                        .param("t", txid)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // -------------------------------------------------------------- decline

    @Test
    void card_decline_returns_402_fails_payment_without_journal_deletes_key_and_retry_reattempts() throws Exception {
        card.mode = CardStub.Mode.DECLINE;
        String idemKey = "idem-card-decl-01";
        String body =
                body("{\"amount\":10000,\"description\":\"Card decline\",\"method\":\"card\",\"cardToken\":\"tok_2\"}");

        var resp = post("/v1/payments", body, authHeaders(idemKey, "req-card-decl-01"));

        assertThat(resp.statusCode()).isEqualTo(402);
        assertThat(parse(resp).at("/code").asText()).isEqualTo("card_declined");

        // one payment row, FAILED, no created/confirmed outbox (no journal lies)
        assertThat(paymentCount()).isEqualTo(1);
        String txid = jdbc.sql("select txid from payments.payments")
                .query(String.class)
                .single();
        assertThat(paymentStatus(txid)).isEqualTo("FAILED");
        // exactly one payment.failed event: no double-failure, no journal (S0 as-built: the
        // registered payment.created op is followed by the failure — same as PIX decline)
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.failed'")
                        .param("t", txid)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                        .param("t", txid)
                        .query(Long.class)
                        .single())
                .isZero();
        String reason = jdbc.sql("select payload -> 'payload' ->> 'reason' from payments.outbox "
                        + "where aggregate_id=:t and type='payment.failed'")
                .param("t", txid)
                .query(String.class)
                .single();
        assertThat(reason).isEqualTo("card_declined");

        // idempotency key deleted: a retry is a FRESH attempt, not a replay
        assertThat(jdbc.sql(
                                "select count(*) from payments.idempotency_keys where merchant_id=:m and idempotency_key=:k")
                        .param("m", MERCHANT)
                        .param("k", idemKey)
                        .query(Long.class)
                        .single())
                .isZero();

        card.mode = CardStub.Mode.APPROVE;
        var retry = post("/v1/payments", body, authHeaders(idemKey, "req-card-decl-02"));
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replay").orElse("")).isNotEqualTo("true");
        assertThat(parse(retry).at("/txid").asText()).isNotEqualTo(txid);
        assertThat(paymentCount()).isEqualTo(2);
    }

    // -------------------------------------------------------------- replay

    @Test
    void card_replay_with_same_key_is_byte_equal_and_writes_zero_new_rows() throws Exception {
        card.mode = CardStub.Mode.APPROVE;
        String idemKey = "idem-card-replay-01";
        String body =
                body("{\"amount\":5000,\"description\":\"Card replay\",\"method\":\"card\",\"cardToken\":\"tok_3\"}");

        var create = post("/v1/payments", body, authHeaders(idemKey, "req-card-rp-01"));
        assertThat(create.statusCode()).isEqualTo(201);
        String txid = parse(create).at("/txid").asText();
        var wh = sendWebhook(String.valueOf(FIXED_NOW_SECS), confirmedBody(txid, E2E, 5000));
        assertThat(wh.statusCode()).isEqualTo(200);
        assertThat(paymentStatus(txid)).isEqualTo("CONFIRMED");
        long rowsAfterConfirm = rowCounts();

        var replay = post("/v1/payments", body, authHeaders(idemKey, "req-card-rp-02"));

        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
        assertThat(replay.body()).isEqualTo(create.body());
        assertThat(parse(replay).at("/txid").asText()).isEqualTo(txid);
        assertThat(rowCounts()).isEqualTo(rowsAfterConfirm);
        // no double journal on replay
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                        .param("t", txid)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // -------------------------------------------------------------- reconciler (S1 backlog #1)

    @Test
    void card_reconciler_confirms_approved_charge_via_get_card_charges_without_webhook() throws Exception {
        card.mode = CardStub.Mode.APPROVE;
        var create = post(
                "/v1/payments",
                body("{\"amount\":10000,\"description\":\"Recon card\",\"method\":\"card\",\"cardToken\":\"tok_4\"}"),
                authHeaders("idem-card-rec-01", "req-card-rec-01"));
        assertThat(create.statusCode()).isEqualTo(201);
        String txid = parse(create).at("/txid").asText();
        assertThat(railOf(txid)).isEqualTo("card");
        // the real reconciler only scans DUE payments: schedule it immediately (past due)
        jdbc.sql("update payments.payments set next_reconcile_at = :due where txid = :t")
                .param("due", java.sql.Timestamp.from(Instant.parse("2026-08-29T11:00:00Z")))
                .param("t", txid)
                .update();

        int changed = reconciler.runOnce(100);

        assertThat(changed).isEqualTo(1);
        assertThat(paymentStatus(txid)).isEqualTo("CONFIRMED");
        assertThat(paymentE2E(txid)).isEqualTo(E2E);
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                        .param("t", txid)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String railOf(String txid) {
        return jdbc.sql("select rail from payments.payments where txid = :t")
                .param("t", txid)
                .query(String.class)
                .single();
    }

    private String paymentStatus(String txid) {
        return jdbc.sql("select status from payments.payments where txid = :t")
                .param("t", txid)
                .query(String.class)
                .single();
    }

    private String paymentE2E(String txid) {
        return jdbc.sql("select end_to_end_id from payments.payments where txid = :t")
                .param("t", txid)
                .query(String.class)
                .single();
    }

    private long paymentFee(String txid) {
        return jdbc.sql("select fee_cents from payments.payments where txid = :t")
                .param("t", txid)
                .query(Long.class)
                .single();
    }

    private long paymentNet(String txid) {
        return jdbc.sql("select net_cents from payments.payments where txid = :t")
                .param("t", txid)
                .query(Long.class)
                .single();
    }

    private List<String> outboxTypes(String txid) {
        return jdbc.sql("select type from payments.outbox where aggregate_id=:t")
                .param("t", txid)
                .query(String.class)
                .list();
    }

    private long rowCounts() {
        Long payments = jdbc.sql("select count(*) from payments.payments")
                .query(Long.class)
                .single();
        Long outbox = jdbc.sql("select count(*) from payments.outbox")
                .query(Long.class)
                .single();
        Long audit = jdbc.sql("select count(*) from payments.audit_log")
                .query(Long.class)
                .single();
        Long webhooks = jdbc.sql("select count(*) from payments.webhook_events")
                .query(Long.class)
                .single();
        return payments + outbox + audit + webhooks;
    }

    private long paymentCount() {
        return jdbc.sql("select count(*) from payments.payments")
                .query(Long.class)
                .single();
    }

    private JsonNode parse(HttpResponse<String> resp) throws IOException {
        return new JsonMapper().readTree(resp.body());
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> authHeaders(String idemKey, String requestId) {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("Authorization", "Bearer " + rawKey);
        m.put("Content-Type", "application/json");
        m.put("Idempotency-Key", idemKey);
        m.put("X-Request-Id", requestId);
        return m;
    }

    private String body(String json) {
        return json;
    }

    private String confirmedBody(String txid, String endToEndId, int amount) {
        return "{\"eventId\":\"psp-evt-card-1\",\"type\":\"" + TYPE + "\",\"txid\":\"" + txid + "\",\"endToEndId\":\""
                + endToEndId + "\",\"amount\":" + amount + ",\"paidAt\":\"" + PAID_AT + "\"}";
    }

    private HttpResponse<String> sendWebhook(String ts, String body) throws Exception {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8));
            String sig = HexFormat.of().formatHex(hash);
            return http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/webhooks/psp"))
                            .header("Content-Type", "application/json")
                            .header("X-PSP-Timestamp", ts)
                            .header("X-PSP-Signature", sig)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Configuration
    static class CardTestConfig {

        @Bean
        Flyway flyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations(
                            "classpath:db/migration/payments",
                            "classpath:db/migration/ledger",
                            "classpath:db/migration/notifications")
                    .baselineOnMigrate(true)
                    .load();
            flyway.migrate();
            return flyway;
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return FIXED_CLOCK;
        }

        @Bean
        String webhookSecret() {
            return SECRET;
        }

        @Bean
        CardStub cardStub() {
            return CARD_STUB;
        }
    }

    /**
     * Shared card-profile stub. The simulator's PSP address is fed to the WHOLE configured context
     * (including the real {@code cardRail} bean) via {@code dargent.psp.base-url}, so no bean
     * overriding or primary juggling is needed — production wiring, pointed at an in-JVM cardinal.
     */
    static final CardStub CARD_STUB = new CardStub();

    static final HttpServer CARD_SERVER = startCardServer();

    private static HttpServer startCardServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/card-charges", CARD_STUB::handle);
            server.createContext("/cobs", c -> c.sendResponseHeaders(404, -1));
            server.start();
            System.setProperty(
                    "dargent.psp.base-url",
                    "http://127.0.0.1:" + server.getAddress().getPort());
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stopCardServer() {
        CARD_SERVER.stop(0);
    }

    /** Approve-or-decline stateful handler for the card PSP profile. */
    static final class CardStub {
        enum Mode {
            APPROVE,
            DECLINE
        }

        volatile Mode mode = Mode.APPROVE;

        void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] respBody;
            int status;
            if ("POST".equals(method) && "/card-charges".equals(path)) {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (mode == Mode.DECLINE) {
                    status = 402;
                    respBody = "{\"code\":\"card_declined\",\"message\":\"Declined by simulator\"}"
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    String txid = extractTxid(requestBody);
                    status = 201;
                    respBody = ("{\"txid\":\"" + txid + "\",\"status\":\"PAID\",\"amount\":"
                                    + extractAmount(requestBody) + ",\"expiresAt\":\"" + PSP_EXPIRES_AT
                                    + "\",\"endToEndId\":\"" + E2E + "\",\"paidAt\":\"" + PAID_AT + "\"}")
                            .getBytes(StandardCharsets.UTF_8);
                }
            } else if ("GET".equals(method) && path.startsWith("/card-charges/")) {
                String txid = path.substring("/card-charges/".length());
                status = 200;
                respBody = ("{\"txid\":\"" + txid + "\",\"status\":\"PAID\",\"amount\":" + AMOUNT
                                + ",\"expiresAt\":\"" + PSP_EXPIRES_AT + "\",\"endToEndId\":\"" + E2E
                                + "\",\"paidAt\":\"" + PAID_AT + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                respBody = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, respBody.length);
            exchange.getResponseBody().write(respBody);
            exchange.close();
        }

        private String extractTxid(String body) {
            int i = body.indexOf("\"txid\"");
            int start = body.indexOf("\"", i + 7) + 1;
            int end = body.indexOf("\"", start);
            return body.substring(start, end);
        }

        private String extractAmount(String body) {
            int i = body.indexOf("\"amount\":") + 9;
            int end = body.indexOf(',', i);
            return body.substring(i, end < 0 ? body.length() : end);
        }
    }
}
