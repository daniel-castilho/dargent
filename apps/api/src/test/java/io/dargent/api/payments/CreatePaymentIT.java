package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.api.web.RequestIdFilter;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * Full-context create-payment ITs (E3R R3): real {@code DargentApiApplication} on a random port, real
 * PostgreSQL 16 (Testcontainers) + Flyway, real security/key filters, real JDK {@link HttpClient},
 * real adapters. The PSP is a JDK {@link HttpServer} stub (no new test dependency) with a recorded,
 * zero-wait sleeper — so the D19 backoff is asserted on the recorded durations, never on wall-clock
 * sleep (AGENTS §5.3).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, CreatePaymentIT.PspTestConfig.class}
)
@Testcontainers
class CreatePaymentIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
    private static final String PSP_EXPIRES_AT = "2026-08-29T12:02:00Z";
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
        jdbc.sql("truncate payments.outbox, payments.idempotency_keys, payments.audit_log, payments.payments, payments.api_keys restart identity cascade").update();
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

    // ------------------------------------------------------------------ happy

    @Test
    void create_payment_returns_201_pending_and_persists_all_canonical_rows() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        String requestId = "req-create-01";
        String idemKey = "idem-happy-01";

        var resp = post("/v1/payments", body("{\"amount\":10000,\"description\":\"Order #123\",\"expiresIn\":\"PT30M\"}"),
                authHeaders(idemKey, requestId));

        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(resp.headers().firstValue("Location").orElse("")).startsWith("/v1/payments/");
        assertThat(resp.headers().firstValue("Idempotent-Replay").isPresent()).isFalse();
        assertThat(resp.headers().firstValue(RequestIdFilter.HEADER)).contains(requestId);
        var json = parse(resp);
        String txid = json.at("/txid").asText();
        assertThat(txid).isNotBlank();
        assertThat(json.at("/status").asText()).isEqualTo("PENDING");
        assertThat(json.at("/amount").asLong()).isEqualTo(10000);
        assertThat(json.at("/currency").asText()).isEqualTo("BRL");
        assertThat(json.at("/expiresAt").asText()).isEqualTo(PSP_EXPIRES_AT);
        assertThat(json.at("/expiresIn").asText()).isEqualTo("PT2M");
        assertThat(json.at("/brcode").asText()).isNotBlank();

        // payments.payments row: PENDING, canonical cents, optimistic version bumped to 1 after PSP
        var pmt = jdbc.sql(
                "select status, amount_cents, version, merchant_id from payments.payments where txid = :txid")
                .param("txid", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getObject(4, UUID.class)})
                .single();
        assertThat(pmt[0]).isEqualTo("PENDING");
        assertThat(pmt[1]).isEqualTo(10000L);
        assertThat(pmt[2]).isEqualTo(1);
        assertThat(pmt[3]).isEqualTo(MERCHANT);

        // idempotency COMPLETED with exact 201 snapshot
        var idem = jdbc.sql(
                "select state, response_status, response_body::text from payments.idempotency_keys "
                        + "where merchant_id=:m and idempotency_key=:k and endpoint=:e")
                .param("m", MERCHANT).param("k", idemKey).param("e", ENDPOINT)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getString(3)})
                .single();
        assertThat(idem[0]).isEqualTo("COMPLETED");
        assertThat(idem[1]).isEqualTo(201);
        assertThat((String) idem[2]).contains(txid);

        // exactly one outbox event, payment.created, with the echoed request id
        var ob = jdbc.sql(
                "select type, request_id, payload::text from payments.outbox where aggregate_id=:t")
                .param("t", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)})
                .list();
        assertThat(ob).hasSize(1);
        assertThat(ob.get(0)[0]).isEqualTo("payment.created");
        assertThat(ob.get(0)[1]).isEqualTo(requestId);
        assertThat((String) ob.get(0)[2]).contains(txid);

        // audit_log keyed by the authenticated key id and the request id
        Integer audit = jdbc.sql(
                "select count(*) from payments.audit_log where merchant_id=:m and actor_key_id=:k "
                        + "and command_name='create_payment' and request_id=:r and aggregate_id=:t")
                .param("m", MERCHANT).param("k", KEY_ID).param("r", requestId).param("t", txid)
                .query(Integer.class).single();
        assertThat(audit).isEqualTo(1);
    }

    @Test
    void replay_with_same_key_returns_identical_201_and_zero_new_rows() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        String idemKey = "idem-replay-01";
        String body = body("{\"amount\":5000,\"description\":\"Replay me\"}");

        var first = post("/v1/payments", body, authHeaders(idemKey, "req-replay-01"));
        String firstTxid = parse(first).at("/txid").asText();
        long rowsAfterFirst = rowCounts();

        var second = post("/v1/payments", body, authHeaders(idemKey, "req-replay-02"));

        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.headers().firstValue("Idempotent-Replay")).contains("true");
        var secondJson = parse(second);
        assertThat(secondJson.at("/txid").asText()).isEqualTo(firstTxid);
        assertThat(secondJson.at("/status").asText()).isEqualTo("PENDING");
        // byte-equal snapshot: no new payments/outbox/audit rows on replay
        assertThat(rowCounts()).isEqualTo(rowsAfterFirst);
    }

    @Test
    void conflicting_body_for_same_key_returns_409_and_creates_nothing() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        String idemKey = "idem-conflict-01";

        post("/v1/payments", body("{\"amount\":5000}"), authHeaders(idemKey, "req-conf-01"));

        var conflict = post("/v1/payments", body("{\"amount\":9999}"), authHeaders(idemKey, "req-conf-02"));

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(parse(conflict).at("/code").asText()).isEqualTo("idempotency_key_conflict");
        assertThat(paymentCount()).isEqualTo(1); // only the first created, none from the conflict
    }

    @Test
    void in_flight_key_returns_425_with_retry_after() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        String idemKey = "idem-inflight-01";
        String flightBody = body("{\"amount\":5000}");
        jdbc.sql("insert into payments.idempotency_keys (merchant_id, idempotency_key, endpoint, request_fingerprint, state) "
                + "values (:m, :k, :e, :fp, 'IN_FLIGHT')")
                .param("m", MERCHANT).param("k", idemKey).param("e", ENDPOINT)
                .param("fp", fingerprint(flightBody.getBytes(StandardCharsets.UTF_8))).update();

        var resp = post("/v1/payments", flightBody, authHeaders(idemKey, "req-inflight-01"));

        assertThat(resp.statusCode()).isEqualTo(425);
        assertThat(resp.headers().firstValue("Retry-After")).contains("1");
        assertThat(parse(resp).at("/code").asText()).isEqualTo("idempotency_key_in_flight");
        assertThat(paymentCount()).isZero();
    }

    // ------------------------------------------------------------------ validation

    @Test
    void missing_idempotency_key_returns_400() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post("/v1/payments", body("{\"amount\":5000}"),
                java.util.Map.of("Authorization", "Bearer " + rawKey, "Content-Type", "application/json"));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(parse(resp).at("/fields/idempotency_key").isMissingNode()).isFalse();
    }

    @Test
    void non_positive_amount_returns_400() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post("/v1/payments", body("{\"amount\":0}"), authHeaders("idem-amount-01", "req-amount-01"));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(parse(resp).at("/fields/amount").isMissingNode()).isFalse();
    }

    @Test
    void malformed_json_returns_400() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post("/v1/payments", "{not json", authHeaders("idem-mal-01", "req-mal-01"));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(parse(resp).at("/fields/body").isMissingNode()).isFalse();
    }

    // ------------------------------------------------------------------ auth / tenancy

    @Test
    void missing_bearer_returns_401() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post("/v1/payments", body("{\"amount\":5000}"), jsonOnlyHeaders());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void unknown_api_key_returns_401() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post("/v1/payments", body("{\"amount\":5000}"),
                java.util.Map.of(
                        "Authorization", "Bearer psp_test_ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ",
                        "Content-Type", "application/json"));
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ PSP exhaustion (scenario 25)

    @Test
    void psp_exhaustion_marks_failed_returns_502_and_records_backoff_sleeps() throws Exception {
        psp.mode = PspStub.Mode.FAIL;
        String idemKey = "idem-exhaust-01";

        var resp = post("/v1/payments", body("{\"amount\":5000}"), authHeaders(idemKey, "req-exhaust-01"));

        assertThat(resp.statusCode()).isEqualTo(502);
        assertThat(parse(resp).at("/code").asText()).isEqualTo("psp_unavailable");

        // three POST attempts (D19: max attempts), with backoff sleeps between them (2 sleeps)
        assertThat(psp.chargeAttempts.get()).isEqualTo(3);
        assertThat(psp.recordedSleeps).hasSize(2); // backoff between attempts 1->2 and 2->3
        assertThat(psp.recordedSleeps).allMatch(v -> v >= 0L);

        // payment FAILED, idempotency row deleted on exhaustion, failed outbox event
        assertThat(paymentCount()).isEqualTo(1);
        String txid = jdbc.sql("select txid from payments.payments where merchant_id = :m")
                .param("m", MERCHANT).query(String.class).single();
        String status = jdbc.sql("select status from payments.payments where txid=:t")
                .param("t", txid).query(String.class).single();
        assertThat(status).isEqualTo("FAILED");

        Integer idemLeft = jdbc.sql(
                "select count(*) from payments.idempotency_keys where merchant_id=:m and idempotency_key=:k and endpoint=:e")
                .param("m", MERCHANT).param("k", idemKey).param("e", ENDPOINT).query(Integer.class).single();
        assertThat(idemLeft).isZero();

        List<String> outboxTypes = jdbc.sql("select type from payments.outbox where aggregate_id=:t")
                .param("t", txid).query(String.class).list();
        assertThat(outboxTypes).contains("payment.failed");
    }

    // ------------------------------------------------------------------ read side (BD-10)

    @Test
    void detail_returns_the_created_payment_for_the_owning_merchant() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var created = post("/v1/payments", body("{\"amount\":12345}"), authHeaders("idem-detail-01", "req-detail-01"));
        String txid = parse(created).at("/txid").asText();

        var detail = http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/payments/" + txid))
                .header("Authorization", "Bearer " + rawKey)
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(detail.statusCode()).isEqualTo(200);
        var dj = parse(detail);
        assertThat(dj.at("/txid").asText()).isEqualTo(txid);
        assertThat(dj.at("/status").asText()).isEqualTo("PENDING");
        assertThat(dj.at("/amount").asLong()).isEqualTo(12345);
        assertThat(dj.at("/brcode").asText()).isNotBlank();
    }

    // ------------------------------------------------------------------ helpers

    private tools.jackson.databind.JsonNode parse(HttpResponse<String> resp) throws Exception {
        return new tools.jackson.databind.json.JsonMapper().readTree(resp.body());
    }

    private HttpResponse<String> post(String path, String body, java.util.Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private java.util.Map<String, String> authHeaders(String idemKey, String requestId) {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("Authorization", "Bearer " + rawKey);
        m.put("Content-Type", "application/json");
        m.put("Idempotency-Key", idemKey);
        m.put("X-Request-Id", requestId);
        return m;
    }

    private java.util.Map<String, String> jsonOnlyHeaders() {
        return java.util.Map.of("Content-Type", "application/json");
    }

    private String body(String json) {
        return json;
    }

    private String fingerprint(byte[] rawBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private long paymentCount() {
        return (Long) jdbc.sql("select count(*) from payments.payments").query(Long.class).single();
    }

    private long rowCounts() {
        Long payments = jdbc.sql("select count(*) from payments.payments").query(Long.class).single();
        Long outbox = jdbc.sql("select count(*) from payments.outbox").query(Long.class).single();
        Long audit = jdbc.sql("select count(*) from payments.audit_log").query(Long.class).single();
        return payments + outbox + audit;
    }

    /** Configures the full context: Flyway, a fixed clock, and a JDK HttpServer PSP stub. */
    @Configuration
    static class PspTestConfig {

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

    /** Stateful HttpHandler for the PSP stub with a recorded, zero-wait sleeper (AGENTS §5.3). */
    static final class PspStub {
        enum Mode { SUCCESS, FAIL }

        volatile Mode mode = Mode.SUCCESS;
        final AtomicInteger chargeAttempts = new AtomicInteger();
        final List<Long> recordedSleeps = new ArrayList<>();

        long sleeper() {
            recordedSleeps.add(1L);
            return 0L;
        }

        void reset() {
            mode = Mode.SUCCESS;
            chargeAttempts.set(0);
            recordedSleeps.clear();
        }

        void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] respBody;
            int status;
            if ("POST".equals(method) && "/cobs".equals(path)) {
                chargeAttempts.incrementAndGet();
                if (mode == Mode.FAIL) {
                    status = 500;
                    respBody = "{\"error\":\"internal\"}".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 200;
                    String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String txid = extractTxid(requestBody);
                    respBody = ("{\"txid\":\"" + txid + "\",\"expiresAt\":\"" + PSP_EXPIRES_AT
                            + "\",\"endToEndId\":\"E2E-1\",\"brcode\":\"000201-terribly-long-brcode\"}")
                            .getBytes(StandardCharsets.UTF_8);
                }
            } else if ("GET".equals(method) && path.startsWith("/cobs/")) {
                String txid = path.substring("/cobs/".length());
                status = 200;
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
