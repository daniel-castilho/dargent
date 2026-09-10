package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.payments.adapter.out.psp.SimulatorChargeAdapter;
import io.dargent.payments.domain.port.out.PspPort;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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
 * M5 S4 reprocess business outcomes: a stored {@code webhook_events} row re-driven through the
 * intake path by an admin holding {@code DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY}. Idempotency is the
 * {@code provider_event_id} UNIQUE — a re-run of the tool can never double-confirm or
 * double-journal. Real actor in {@code audit_log} ({@code webhook_reprocessed}); the money-path
 * audit keeps the Webhook-UNIT sentinel (BD-14).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {DargentApiApplication.class, WebhookReprocessIT.WebhookTestConfig.class},
        properties = "dargent.psp.webhook-secret=dev-only-secret")
@Testcontainers
class WebhookReprocessIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN_KEY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
    private static final long FIXED_NOW_SECS = FIXED_CLOCK.instant().getEpochSecond();
    private static final String PSP_EXPIRES_AT = "2026-08-29T12:02:00Z";
    private static final String SECRET = "dev-only-secret";
    private static final String TYPE = "payment.confirmed";
    private static final UUID SENTINEL = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PspStub psp;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    @org.springframework.test.context.DynamicPropertySource
    static void env(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY", () -> ADMIN_RAW_KEY);
    }

    /** The admin raw key the env designates; its hash is inserted as an ACTIVE api key. */
    private static final String ADMIN_RAW_KEY = "psp-admin-key-" + UUID.randomUUID();

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
        jdbc.sql("insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'admin-key', :prefix, :hash, now(), null)")
                .param("id", ADMIN_KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(ADMIN_RAW_KEY))
                .param("hash", ApiKeyHasher.hash(ADMIN_RAW_KEY))
                .update();
        psp.reset();
    }

    // ------------------------------------------------------------------ re-drive outcomes

    @Test
    void reprocess_of_RECEIVED_row_confirms_payment_and_audits_the_real_admin_actor() throws Exception {
        String txid = createPayment("webhook-reproc-01");
        String endToEndId = endToEndId("01");
        String providerEventId = endToEndId + "|" + TYPE;
        seedWebhookRow(providerEventId, txid, confirmedBody(txid, endToEndId, 10000), true, "RECEIVED");

        var resp = reprocess(providerEventId, ADMIN_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("processed");

        var pmt = payment(txid);
        assertThat(pmt[0]).isEqualTo("CONFIRMED");
        assertThat(pmt[1]).isEqualTo(endToEndId);
        assertThat(pmt[2]).isEqualTo(100L); // fee 100 bps
        assertThat(pmt[3]).isEqualTo(9900L); // net

        // webhook event PROCESSED through the intake core
        assertThat(jdbc.sql("select status from payments.webhook_events where provider_event_id = :p")
                        .param("p", providerEventId)
                        .query(String.class)
                        .single())
                .isEqualTo("PROCESSED");

        // exactly one outbox payment.confirmed (dedupe = idempotent by design)
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                        .param("t", txid)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // money-path audit keeps the webhook sentinel actor (BD-14)…
        UUID confirmActor = jdbc.sql(
                        "select actor_key_id from payments.audit_log where command_name='confirm_from_webhook' and aggregate_id=:t")
                .param("t", txid)
                .query(UUID.class)
                .single();
        assertThat(confirmActor).isEqualTo(SENTINEL);
        // …and the admin reprocess action records the REAL principal (house pattern), keyed to the
        // payment txid (the money aggregate — audit_log.aggregate_id is varchar(25), so the 51-char
        // provider_event_id "p|type" can never be the aggregate).
        UUID reprocActor = jdbc.sql(
                        "select actor_key_id from payments.audit_log where command_name='webhook_reprocessed' and aggregate_id=:t")
                .param("t", txid)
                .query(UUID.class)
                .single();
        assertThat(reprocActor).isEqualTo(ADMIN_KEY_ID);
    }

    @Test
    void reprocess_of_IGNORED_row_confirms_once_payment_exists() throws Exception {
        // The operator case: webhook arrived before the payment was visible → IGNORED. Once the
        // payment exists, the admin re-drive confirms it through the intake core.
        String txid = createPayment("webhook-reproc-02");
        String endToEndId = endToEndId("02");
        String providerEventId = endToEndId + "|" + TYPE;
        seedWebhookRow(providerEventId, txid, confirmedBody(txid, endToEndId, 10000), true, "IGNORED");

        var resp = reprocess(providerEventId, ADMIN_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("processed");
        assertThat(payment(txid)[0]).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("select count(*) from payments.outbox where type='payment.confirmed'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void reprocess_of_PROCESSED_row_is_idempotent_duplicate_without_double_journaling() throws Exception {
        String txid = createPayment("webhook-reproc-03");
        String endToEndId = endToEndId("03");
        String body = confirmedBody(txid, endToEndId, 10000);
        var intake = sendWebhook(String.valueOf(FIXED_NOW_SECS), body);
        assertThat(intake.statusCode()).isEqualTo(200); // row PROCESSED via the real intake

        var resp = reprocess(endToEndId + "|" + TYPE, ADMIN_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("duplicate");
        // no double-outbox, no double-journal: still one payment.confirmed envelope
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                        .param("t", txid)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select status from payments.webhook_events where provider_event_id=:p")
                        .param("p", endToEndId + "|" + TYPE)
                        .query(String.class)
                        .single())
                .isEqualTo("PROCESSED");
    }

    @Test
    void reprocess_of_signature_invalid_attack_evidence_is_409_and_untouched() throws Exception {
        // Fail-closed (AGENTS §4.4): signature-valid=false rows are immutable attack audit; an admin
        // re-drive must never turn them into a confirmation.
        String txid = createPayment("webhook-reproc-04");
        String endToEndId = endToEndId("04");
        String providerEventId = endToEndId + "|" + TYPE;
        seedWebhookRow(providerEventId, txid, confirmedBody(txid, endToEndId, 10000), false, "IGNORED");

        var resp = reprocess(providerEventId, ADMIN_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(parse(resp).at("/code").asText()).isEqualTo("invalid_state");
        assertThat(payment(txid)[0]).isEqualTo("PENDING");
        assertThat(jdbc.sql("select count(*) from payments.outbox where type='payment.confirmed'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select signature_valid, status from payments.webhook_events where provider_event_id=:p")
                        .param("p", providerEventId)
                        .query((rs, i) -> new Object[] {rs.getBoolean(1), rs.getString(2)})
                        .single()[1])
                .isEqualTo("IGNORED");
        assertThat(jdbc.sql("select count(*) from payments.audit_log where command_name='webhook_reprocessed'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void reprocess_of_unknown_provider_event_id_is_404() throws Exception {
        var resp = reprocess("E9UNKNOWN00000000000000000000X|payment.confirmed", ADMIN_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(parse(resp).at("/code").asText()).isEqualTo("not_found");
    }

    @Test
    void reprocess_of_amount_mismatch_row_re_ignores_with_200_and_audit() throws Exception {
        String txid = createPayment("webhook-reproc-05");
        String endToEndId = endToEndId("05");
        String providerEventId = endToEndId + "|" + TYPE;
        seedWebhookRow(providerEventId, txid, confirmedBody(txid, endToEndId, 9999), true, "IGNORED");

        var resp = reprocess(providerEventId, ADMIN_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("ignored");
        assertThat(parse(resp).at("/reason").asText()).isEqualTo("amount mismatch");
        assertThat(payment(txid)[0]).isEqualTo("PENDING");
        assertThat(jdbc.sql("select count(*) from payments.outbox where type='payment.confirmed'")
                        .query(Long.class)
                        .single())
                .isZero();
        // the admin did re-drive the row — audited with the real actor (keyed to the payment txid)
        assertThat(jdbc.sql("select count(*) from payments.audit_log where command_name='webhook_reprocessed'"
                                + " and aggregate_id=:t")
                        .param("t", txid)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String createPayment(String idemKey) throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post(
                "/v1/payments",
                "{\"amount\":10000,\"description\":\"Webhook IT\",\"expiresIn\":\"PT30M\"}",
                authHeaders(idemKey));
        assertThat(resp.statusCode()).isEqualTo(201);
        return parse(resp).at("/txid").asText();
    }

    private void seedWebhookRow(
            String providerEventId, String txid, String body, boolean signatureValid, String status) {
        jdbc.sql("insert into payments.webhook_events "
                        + "(id, provider_event_id, psp_event_id, type, txid, payload_raw, signature_valid, status, received_at) "
                        + "values (:id, :p, 'psp-it', :type, :txid, :body::jsonb, :sig, :status, now())")
                .param("id", UUID.randomUUID())
                .param("p", providerEventId)
                .param("type", TYPE)
                .param("txid", txid)
                .param("body", body)
                .param("sig", signatureValid)
                .param("status", status)
                .update();
    }

    private HttpResponse<String> reprocess(String providerEventId, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/webhooks/reprocess"))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "req-reproc")
                .POST(HttpRequest.BodyPublishers.ofString("{\"providerEventId\":\"" + providerEventId + "\"}"));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendWebhook(String ts, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/webhooks/psp"))
                        .header("Content-Type", "application/json")
                        .header("X-PSP-Timestamp", ts)
                        .header("X-PSP-Signature", sign(ts, body))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> authHeaders(String idemKey) {
        return Map.of(
                "Authorization",
                "Bearer " + rawKey,
                "Content-Type",
                "application/json",
                "Idempotency-Key",
                idemKey,
                "X-Request-Id",
                "req-" + idemKey);
    }

    private JsonNode parse(HttpResponse<String> resp) throws IOException {
        return new JsonMapper().readTree(resp.body());
    }

    /** Valid PIX endToEndId: exactly E + 31 alphanumeric (EndToEndId requires 32 chars total). */
    private static String endToEndId(String tag) {
        String alnum = ("REPROC" + tag + "0".repeat(31)).substring(0, 31);
        return "E" + alnum;
    }

    private String confirmedBody(String txid, String endToEndId, int amount) {
        return "{\"eventId\":\"psp-evt-1\",\"type\":\"" + TYPE
                + "\",\"txid\":\"" + txid + "\",\"endToEndId\":\"" + endToEndId
                + "\",\"amount\":" + amount + ",\"paidAt\":\"" + PSP_EXPIRES_AT + "\"}";
    }

    private String sign(String ts, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((ts + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object[] payment(String txid) {
        return jdbc.sql("select status, end_to_end_id, fee_cents, net_cents from payments.payments where txid = :txid")
                .param("txid", txid)
                .query((rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)})
                .single();
    }

    @Configuration
    static class WebhookTestConfig {

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
        PspStub pspStub() {
            return new PspStub();
        }

        @Bean
        HttpServer pspServer(PspStub psp) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/cobs", psp::handle);
            server.start();
            return server;
        }

        @Bean
        @Primary
        PspPort pspTestPort(HttpServer server, PspStub psp) {
            int port = server.getAddress().getPort();
            return new SimulatorChargeAdapter("http://127.0.0.1:" + port, 3, Duration.ofMillis(20), psp::sleeper);
        }
    }

    static final class PspStub {
        enum Mode {
            SUCCESS,
            FAIL
        }

        volatile Mode mode = Mode.SUCCESS;
        volatile long latencyMs = 0L;

        long sleeper() {
            return 0L;
        }

        void reset() {
            mode = Mode.SUCCESS;
            latencyMs = 0L;
        }

        void handle(HttpExchange exchange) throws IOException {
            if (latencyMs > 0) {
                try {
                    Thread.sleep(latencyMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            byte[] respBody;
            int status;
            if ("POST".equals(method) && "/cobs".equals(path)) {
                status = 200;
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String txid = extractTxid(requestBody);
                respBody = ("{\"txid\":\"" + txid + "\",\"expiresAt\":\"" + PSP_EXPIRES_AT
                                + "\",\"endToEndId\":\"E2E-1\",\"brcode\":\"000201-terribly-long-brcode\"}")
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
    }
}
