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
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * Webhook intake ITs (E4 spec §5.1–§5.4): full context on a random port, real PostgreSQL + Flyway,
 * real security, real JDK {@link HttpClient}. Signatures are produced by a LOCAL hand-signer
 * (HMAC-SHA256 over ts + "." + rawBody) — no simulator WebhookSigner, no WireMock on the inbound side.
 * The fixed {@link Clock} bean drives both anti-replay (stale -301s) and processed_at timestamps.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, WebhookIntakeIT.WebhookTestConfig.class},
    properties = "dargent.psp.webhook-secret=dev-only-secret"
)
@Testcontainers
class WebhookIntakeIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
    private static final long FIXED_NOW_SECS = FIXED_CLOCK.instant().getEpochSecond();
    private static final String PSP_EXPIRES_AT = "2026-08-29T12:02:00Z";
    private static final String SECRET = "dev-only-secret";
    private static final String TYPE = "payment.confirmed";
    private static final String ENDPOINT = "POST /v1/payments";

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

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                + "payments.audit_log, payments.payments, payments.api_keys restart identity cascade").update();
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
        psp.reset();
    }

    // ------------------------------------------------------------------ valid intake

    @Test
    void valid_confirmed_webhook_confirms_payment_writes_outbox_and_marks_PROCESSED() throws Exception {
        String txid = createPayment("webhook-happy-01");
        String endToEndId = "E9040381234567890123456789012345";
        String body = confirmedBody(txid, endToEndId, 10000);
        String ts = String.valueOf(FIXED_NOW_SECS);

        var resp = sendWebhook(ts, body);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("processed");

        var pmt = payment(txid);
        assertThat(pmt[0]).isEqualTo("CONFIRMED");
        assertThat(pmt[1]).isEqualTo(endToEndId);
        assertThat(pmt[2]).isEqualTo(100L);   // fee 100 bps
        assertThat(pmt[3]).isEqualTo(9900L);  // net

        // webhook event PROCESSED
        String status = jdbc.sql(
                "select status from payments.webhook_events where provider_event_id = :p")
                .param("p", endToEndId + "|" + TYPE).query(String.class).single();
        assertThat(status).isEqualTo("PROCESSED");

        // exactly one outbox payment.confirmed with {amount, fee, net, late:false}
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single()).isEqualTo(1);
        String payload = jdbc.sql("select payload::text from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(String.class).single();
        var pj = new tools.jackson.databind.json.JsonMapper().readTree(payload);
        assertThat(pj.at("/amount").asLong()).isEqualTo(10000);
        assertThat(pj.at("/fee").asLong()).isEqualTo(100);
        assertThat(pj.at("/net").asLong()).isEqualTo(9900);
        assertThat(pj.at("/late").asBoolean()).isFalse();

        // BD-14: audit row has sentinel actor_key_id (webhook has no API key; BD-14 ratified sentinel)
        UUID auditActor = jdbc.sql(
                "select actor_key_id from payments.audit_log where command_name='confirm_from_webhook' and aggregate_id=:t")
                .param("t", txid).query(UUID.class).optional().orElseThrow();
        assertThat(auditActor).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void duplicate_webhook_returns_duplicate_and_only_one_outbox_row() throws Exception {
        String txid = createPayment("webhook-dup-01");
        String endToEndId = "E9DUP01234567890123456789012345X";
        String body = confirmedBody(txid, endToEndId, 10000);
        String ts = String.valueOf(FIXED_NOW_SECS);

        var first = sendWebhook(ts, body);
        assertThat(first.statusCode()).isEqualTo(200);
        var second = sendWebhook(ts, body);

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(parse(second).at("/status").asText()).isEqualTo("duplicate");

        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void replay_of_RECEIVED_row_reprocesses_to_PROCESSED() throws Exception {
        // Scenario 10: a prior crash left the webhook row as RECEIVED; a redelivery of the same
        // provider_event_id must reprocess from the stored payload_raw (never double-confirm).
        String txid = createPayment("webhook-10-01");
        String endToEndId = "E910000000000000000000000000001X";
        String providerEventId = endToEndId + "|" + TYPE;
        jdbc.sql("insert into payments.webhook_events "
                + "(id, provider_event_id, psp_event_id, type, txid, payload_raw, signature_valid, status, received_at) "
                + "values (:id, :p, 'psp-10', :type, :txid, :body::jsonb, true, 'RECEIVED', now())")
                .param("id", UUID.randomUUID()).param("p", providerEventId).param("type", TYPE)
                .param("txid", txid).param("body", confirmedBody(txid, endToEndId, 10000)).update();

        String body = confirmedBody(txid, endToEndId, 10000);
        var resp = sendWebhook(String.valueOf(FIXED_NOW_SECS), body);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("processed");
        assertThat(jdbc.sql("select status from payments.webhook_events where provider_event_id=:p")
                .param("p", providerEventId).query(String.class).single()).isEqualTo("PROCESSED");
        assertThat(payment(txid)[0]).isEqualTo("CONFIRMED");
    }

    @Test
    void atomicity_happy_path_payment_and_outbox_created_together() throws Exception {
        // Verifies the real TransactionTemplate makes confirm + outbox atomic:
        // on success, both payment confirmed AND outbox row appear;
        // on failure (simulated in unit test with fakes), neither appears.
        String txid = createPayment("webhook-atomic-01");
        String endToEndId = "E9ATOMIC00000000000000000000XXXX";
        String body = confirmedBody(txid, endToEndId, 10000);
        String ts = String.valueOf(FIXED_NOW_SECS);

        var resp = sendWebhook(ts, body);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("processed");
        assertThat(payment(txid)[0]).isEqualTo("CONFIRMED");
        // Atomicity: payment confirmed AND outbox row both present
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select status from payments.webhook_events where provider_event_id=:p")
                .param("p", endToEndId + "|" + TYPE).query(String.class).single()).isEqualTo("PROCESSED");
    }

    // ------------------------------------------------------------------ fail-closed signatures

    @Test
    void invalid_signature_returns_401_and_persists_raw_attack_evidence() throws Exception {
        String txid = createPayment("webhook-bad-sig");
        String body = confirmedBody(txid, "E9BAD0000000000000000000000001X", 10000);
        String ts = String.valueOf(FIXED_NOW_SECS);
        String badSig = "0".repeat(64);

        HttpResponse<String> resp = postWebhook(ts, body, badSig);

        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(parse(resp).at("/code").asText()).isEqualTo("invalid_signature");

        // attack-audit row persisted with signature_valid=false
        var row = jdbc.sql(
                "select signature_valid, status, payload_raw::text from payments.webhook_events "
                        + "where provider_event_id = :p")
                .param("p", "raw|" + sha256Hex(body.getBytes(StandardCharsets.UTF_8)))
                .query((rs, i) -> new Object[]{rs.getBoolean(1), rs.getString(2), rs.getString(3)})
                .single();
        assertThat(row[0]).isEqualTo(false);
        assertThat(row[1]).isEqualTo("IGNORED");
        var storedPayload = new tools.jackson.databind.json.JsonMapper().readTree((String) row[2]);
        var expectedPayload = new tools.jackson.databind.json.JsonMapper().readTree(body);
        assertThat(storedPayload).isEqualTo(expectedPayload);
        // payment untouched
        assertThat(payment(txid)[0]).isEqualTo("PENDING");
    }

    @Test
    void stale_timestamp_returns_401_signature_expired_via_injected_clock() throws Exception {
        String txid = createPayment("webhook-stale");
        String body = confirmedBody(txid, "E9STALE00000000000000000000001X", 10000);
        // -301s relative to the FIXED clock -> outside the 300s replay window (AGENTS §5.3: no sleep)
        String staleTs = String.valueOf(FIXED_NOW_SECS - 301);

        var resp = sendWebhook(staleTs, body);

        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(parse(resp).at("/code").asText()).isEqualTo("signature_expired");
        assertThat(payment(txid)[0]).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ ignored (sanity)

    @Test
    void unknown_webhook_type_is_ignored_with_200_and_no_outbox() throws Exception {
        String txid = createPayment("webhook-unk-type");
        String endToEndId = "E9UNKTYPE000000000000000000000X";
        String body = confirmedBody(txid, endToEndId, 10000).replace(TYPE, "payment.unknown");
        String providerEventId = endToEndId + "|payment.unknown";

        var resp = sendWebhook(String.valueOf(FIXED_NOW_SECS), body);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("ignored");
        assertThat(jdbc.sql("select status from payments.webhook_events where provider_event_id=:p")
                .param("p", providerEventId).query(String.class).single()).isEqualTo("IGNORED");
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single()).isZero();
        assertThat(payment(txid)[0]).isEqualTo("PENDING");
    }

    @Test
    void unknown_txid_is_ignored_with_200_and_no_outbox() throws Exception {
        createPayment("webhook-unk-txid");
        String unknownTxid = "ZZZZZZZZZZZZZZZZZZZZZZZZZ";
        String endToEndId = "E9UNKTXID0000000000000000000X";
        String body = confirmedBody(unknownTxid, endToEndId, 10000);

        var resp = sendWebhook(String.valueOf(FIXED_NOW_SECS), body);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("ignored");
        assertThat(jdbc.sql("select count(*) from payments.outbox where type='payment.confirmed'").query(Long.class).single()).isZero();
    }

    @Test
    void amount_mismatch_is_ignored_with_200_and_no_outbox() throws Exception {
        String txid = createPayment("webhook-amt");
        String endToEndId = "E9AMOUNT000000000000000000000X";
        String body = confirmedBody(txid, endToEndId, 9999); // payment is 10000

        var resp = sendWebhook(String.valueOf(FIXED_NOW_SECS), body);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("ignored");
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single()).isZero();
        assertThat(payment(txid)[0]).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ helpers

    private String createPayment(String idemKey) throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post("/v1/payments", "{\"amount\":10000,\"description\":\"Webhook IT\",\"expiresIn\":\"PT30M\"}",
                authHeaders(idemKey));
        assertThat(resp.statusCode()).isEqualTo(201);
        return parse(resp).at("/txid").asText();
    }

    private HttpResponse<String> sendWebhook(String ts, String body) throws Exception {
        return postWebhook(ts, body, sign(ts, body));
    }

    private HttpResponse<String> postWebhook(String ts, String body, String sig) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/webhooks/psp"))
                .header("Content-Type", "application/json")
                .header("X-PSP-Timestamp", ts)
                .header("X-PSP-Signature", sig)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
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
                "Authorization", "Bearer " + rawKey,
                "Content-Type", "application/json",
                "Idempotency-Key", idemKey,
                "X-Request-Id", "req-" + idemKey);
    }

    private JsonNode parse(HttpResponse<String> resp) throws IOException {
        return new JsonMapper().readTree(resp.body());
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

    private String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object[] payment(String txid) {
        return jdbc.sql(
                "select status, end_to_end_id, fee_cents, net_cents from payments.payments where txid = :txid")
                .param("txid", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)})
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
                            "classpath:db/migration/notifications"
                    )
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
        enum Mode { SUCCESS, FAIL }

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
