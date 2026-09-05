package io.dargent.api.config;

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
import java.util.Map;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Production lockdown IT (E11 S3, observability-e11-prompt §S3): with the {@code prod} profile,
 * Swagger/api-docs are absent, actuator is denied on the main listener and served only on the
 * isolated management port (health UP, detail-free), business endpoints enforce the API key
 * (401 without, 201 with) and tenant-of-credential (cross-merchant GET → 404, never 403).
 *
 * Management port pinned to 9091 in tests (9090 is used by ManagementPortIT); main listener on
 * RANDOM_PORT. PSP is a JDK HttpServer stub (proven infra from CreatePaymentIT/JsonLogCorrelationIT)
 * so POST /v1/payments yields a real 201.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, ProductionLockdownIT.TestConfig.class},
    properties = {
        "spring.profiles.active=prod",
        "management.server.port=9091",
        "DARGENT_DB_PASSWORD=prod-test-password-that-is-at-least-32-chars-long",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        "PSP_BASE_URL=http://psp-stub:8090",
        "PSP_WEBHOOK_SECRET=prod-test-webhook-secret-that-is-long-enough",
        "dargent.psp.webhook-secret=prod-test-webhook-secret-that-is-long-enough",
        "dargent.relay.enabled=false"
    }
)
@Testcontainers
class ProductionLockdownIT {

    private static final int MANAGEMENT_PORT = 9091;
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_MERCHANT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_KEY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
    private static final String PSP_EXPIRES_AT = "2026-08-29T12:02:00Z";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PspStub psp;

    @LocalServerPort
    int mainPort;

    @Autowired
    Environment env;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + mainPort;
        jdbc.sql("truncate payments.outbox, payments.idempotency_keys, payments.audit_log, payments.payments, payments.api_keys restart identity cascade").update();
        // Owner merchant's key (rawKey, prefix psp_test_) stays active for the tests.
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

    @Test
    void mainPort_swaggerAndApiDocs_areAbsent() throws Exception {
        var swaggerResp = get("/swagger-ui.html", null);
        var apiDocsResp = get("/v3/api-docs", null);

        // Absent in prod (404, or 401 when denyAll answers first)
        assertThat(swaggerResp.statusCode())
                .as("swagger-ui must be absent in prod")
                .isIn(404, 401);
        assertThat(apiDocsResp.statusCode())
                .as("v3/api-docs must be absent in prod")
                .isIn(404, 401);
    }

    @Test
    void mainPort_actuatorEndpoints_areDenied() throws Exception {
        // S0 regression: denyAll on the main listener fails actuator closed (no permissive fallback)
        int health = get("/actuator/health", null).statusCode();
        int prom = get("/actuator/prometheus", null).statusCode();
        int info = get("/actuator/info", null).statusCode();

        assertThat(health).as("actuator/health on main port must be denied").isIn(401, 403, 404);
        assertThat(prom).as("actuator/prometheus on main port must be denied").isIn(401, 403, 404);
        assertThat(info).as("actuator/info on main port must be denied").isIn(401, 403, 404);
    }

    @Test
    void managementPort_servesHealthAndPrometheus() throws Exception {
        String mgmtBase = "http://localhost:" + MANAGEMENT_PORT;

        var healthReq = HttpRequest.newBuilder()
                .uri(URI.create(mgmtBase + "/actuator/health"))
                .GET().build();
        var healthResp = http.send(healthReq, HttpResponse.BodyHandlers.ofString());

        // health is 200 UP with no detail groups (show-details: never)
        assertThat(healthResp.statusCode()).isEqualTo(200);
        assertThat(healthResp.body()).contains("\"status\":\"UP\"");
        assertThat(healthResp.body()).doesNotContain("details");
        assertThat(healthResp.body()).doesNotContain("db");

        var promReq = HttpRequest.newBuilder()
                .uri(URI.create(mgmtBase + "/actuator/prometheus"))
                .GET().build();
        var promResp = http.send(promReq, HttpResponse.BodyHandlers.ofString());

        assertThat(promResp.statusCode()).isEqualTo(200);
        assertThat(promResp.body()).contains("jvm_memory_used_bytes");
        assertThat(promResp.body()).contains("jvm_threads_live_threads");

        var infoReq = HttpRequest.newBuilder()
                .uri(URI.create(mgmtBase + "/actuator/info"))
                .GET().build();
        var infoResp = http.send(infoReq, HttpResponse.BodyHandlers.ofString());

        assertThat(infoResp.statusCode()).isEqualTo(200);
        assertThat(infoResp.body()).isNotBlank();
    }

    @Test
    void businessEndpoint_postWithoutKey_returns401() throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/payments"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":1000}"))
                .build();
        var resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode())
                .as("business endpoints without a key must be 401")
                .isEqualTo(401);
    }

    @Test
    void businessEndpoint_postWithKey_returns201() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        var resp = post("/v1/payments", "{\"amount\":10000,\"description\":\"Order #123\",\"expiresIn\":\"PT30M\"}",
                Map.of(
                        "Authorization", "Bearer " + rawKey,
                        "Content-Type", "application/json",
                        "Idempotency-Key", "idem-lock-01",
                        "X-Request-Id", "req-lock-01"));

        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(parse(resp).at("/status").asText()).isEqualTo("PENDING");
        assertThat(parse(resp).at("/expiresAt").asText()).isEqualTo(PSP_EXPIRES_AT);
    }

    @Test
    void crossMerchant_access_returns404() throws Exception {
        psp.mode = PspStub.Mode.SUCCESS;
        // owner creates a payment via the real API (proven shape: CreatePaymentIT)
        var created = post("/v1/payments", "{\"amount\":7777}",
                Map.of(
                        "Authorization", "Bearer " + rawKey,
                        "Content-Type", "application/json",
                        "Idempotency-Key", "idem-lock-xt",
                        "X-Request-Id", "req-lock-xt"));
        assertThat(created.statusCode()).isEqualTo(201);
        String txid = parse(created).at("/txid").asText();

        // AS-BUILT: cross-merchant GET is 404 from the query (E3 §3.7 / D21 — never 403; proven at
        // CreatePaymentIT.cross_tenant_detail_is_404_from_the_query_never_403). Revoke the owner key
        // first so the other merchant's key can take the shared dev key_prefix
        // (uq_api_keys_key_prefix_active is partial over non-revoked keys).
        jdbc.sql("update payments.api_keys set revoked_at = now() where id = :id")
                .param("id", KEY_ID).update();
        String otherRawKey = ApiKeyHasher.generateRawKey();
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'other-key', :prefix, :hash, now(), null)")
                .param("id", OTHER_KEY_ID)
                .param("merchant", OTHER_MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(otherRawKey))
                .param("hash", ApiKeyHasher.hash(otherRawKey))
                .update();

        var detail = get("/v1/payments/" + txid, otherRawKey);

        assertThat(detail.statusCode())
                .as("cross-merchant access is 404 from the query, never 403 (AGENTS §3.7)")
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> get(String path, String rawKey) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET();
        if (rawKey != null) {
            builder.header("Authorization", "Bearer " + rawKey);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode parse(HttpResponse<String> resp) throws Exception {
        return new JsonMapper().readTree(resp.body());
    }

    // ------------------------------------------------------------------ test config (proven infra from CreatePaymentIT/JsonLogCorrelationIT)

    @Configuration
    static class TestConfig {

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
            return "prod-test-webhook-secret-that-is-long-enough";
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
        volatile long latencyMs = 0L;
        final AtomicInteger chargeAttempts = new AtomicInteger();

        long sleeper() {
            return 0L;
        }

        void reset() {
            mode = Mode.SUCCESS;
            latencyMs = 0L;
            chargeAttempts.set(0);
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