package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M5 S2 (D3) — the idempotent-replay cache proofs, against REAL Redis + real PostgreSQL via
 * Testcontainers (m5-spec §6: fail-open IT transcript is the evidence). The cache is enabled for
 * this context only ({@code dargent.cache.redis.enabled=true}); production default is OFF.
 *
 * <p>Covers the four S2 guarantees: (1) a completed replay served from cache is byte-equal to the
 * original 201 (BD-6), with the hit counted; (2) the cached record preserves the request fingerprint
 * so a conflicting body still answers 409 (a cache that dropped it would degrade every replay to
 * 409 — or worse, 201 for a different body); (3) the cached snapshot is TTL-bounded in Redis and
 * evicted when the key row is deleted (exhaustion) — a deleted key is NEVER replayed stale; (4)
 * FAIL-OPEN: Redis stopped mid-test, the replay still answers byte-equal from the DB fallback and
 * the failure is counted, never surfaced (the cache is an optimization, not a dependency).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {DargentApiApplication.class, ReplayCacheIT.ReplayCacheTestConfig.class},
        properties = {
            "dargent.psp.webhook-secret=dev-only-secret",
            "dargent.relay.enabled=false",
            "dargent.psp.create-backoff-base-ms=1"
        })
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReplayCacheIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
    private static final String PSP_EXPIRES_AT = "2026-08-29T12:02:00Z";
    private static final String ENDPOINT = "POST /v1/payments";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void cacheProperties(DynamicPropertyRegistry registry) {
        // The ONE hot read path (D3): idempotent-replay lookup. Enabled for this IT context only.
        registry.add("dargent.cache.redis.enabled", () -> "true");
        registry.add("dargent.cache.redis.uri", () -> "redis://localhost:" + redis.getMappedPort(6379));
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry metrics;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    PixStub psp;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate payments.outbox, payments.idempotency_keys, payments.audit_log, payments.payments, "
                        + "payments.api_keys restart identity cascade")
                .update();
        jdbc.sql("insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
        psp.reset();
    }

    // --------------------------------------------- 1. cached replay is byte-equal

    @Test
    @Order(1)
    void completed_replay_is_served_from_cache_byte_equal_and_counted_as_a_hit() throws Exception {
        String idemKey = "idem-cache-hit-01";

        var first = post("/v1/payments", body("{\"amount\":5000}"), authHeaders(idemKey, "req-cache-hit-01"));
        assertThat(first.statusCode()).isEqualTo(201);

        double hitsBefore = counter("dargent.cache.hits");
        double missesBefore = counter("dargent.cache.misses");
        long rowsBefore = rowCounts();

        var replay = post("/v1/payments", body("{\"amount\":5000}"), authHeaders(idemKey, "req-cache-hit-02"));

        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replay")).contains("true");
        // BD-6: replay is byte-equal to the original 201, cache or no cache
        assertThat(replay.body()).isEqualTo(first.body());
        // the replay was served by the cache: a hit counted, no new DB miss on this key
        assertThat(counter("dargent.cache.hits")).isGreaterThan(hitsBefore);
        assertThat(counter("dargent.cache.misses")).isEqualTo(missesBefore);
        // zero side effects: no new payments/outbox/audit rows
        assertThat(rowCounts()).isEqualTo(rowsBefore);
    }

    // --------------------------------------------- 2. fingerprint survives the cache

    @Test
    @Order(2)
    void conflicting_body_for_a_cached_key_still_returns_409() throws Exception {
        String idemKey = "idem-cache-conflict-01";

        var first = post("/v1/payments", body("{\"amount\":5000}"), authHeaders(idemKey, "req-cache-cf-01"));
        assertThat(first.statusCode()).isEqualTo(201);

        // same key, DIFFERENT body: must be 409 idempotency_key_conflict — the cached snapshot
        // carries the original request fingerprint, so the conflict decision is unchanged.
        var conflict = post("/v1/payments", body("{\"amount\":7000}"), authHeaders(idemKey, "req-cache-cf-02"));

        assertThat(conflict.statusCode()).isEqualTo(409);
        var json = parse(conflict);
        assertThat(json.at("/code").asText()).isEqualTo("idempotency_key_conflict");
    }

    // --------------------------------------------- 3. TTL-bounded + evict-on-delete

    @Test
    @Order(3)
    void cached_snapshot_is_ttl_bounded_in_redis() throws Exception {
        String idemKey = "idem-cache-ttl-01";

        var created = post("/v1/payments", body("{\"amount\":3000}"), authHeaders(idemKey, "req-cache-ttl-01"));
        assertThat(created.statusCode()).isEqualTo(201);

        String cacheKey = cacheKey(idemKey);
        Boolean present = redisTemplate.hasKey(cacheKey);
        assertThat(present).isTrue();
        // "revoked key must never outlive TTL" (m5-backlog §S2.3): the entry is time-bounded in
        // Redis, so expiry alone bounds the staleness window even if an evict were ever missed.
        Long ttlSeconds = redisTemplate.getExpire(cacheKey);
        assertThat(ttlSeconds).isNotNull().isPositive();
    }

    @Test
    @Order(4)
    void exhausted_key_is_never_replayed_from_cache_and_retry_starts_a_fresh_payment() throws Exception {
        String idemKey = "idem-cache-exhaust-01";

        psp.mode = PixStub.Mode.FAIL;
        var exhausted = post("/v1/payments", body("{\"amount\":5000}"), authHeaders(idemKey, "req-cache-ex-01"));
        assertThat(exhausted.statusCode()).isEqualTo(502);

        // runExhaustion deletes the key row and evicts the cache entry: nothing left to replay
        assertThat(redisTemplate.hasKey(cacheKey(idemKey))).isFalse();

        // retry with the SAME key: a FRESH payment, never a stale replay of the exhausted one
        psp.mode = PixStub.Mode.SUCCESS;
        var retry = post("/v1/payments", body("{\"amount\":5000}"), authHeaders(idemKey, "req-cache-ex-02"));
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replay").orElse("")).isNotEqualTo("true");
        assertThat(paymentCount()).isEqualTo(2);
        String freshTxid = parse(retry).at("/txid").asText();
        String exhaustedTxid = jdbc.sql("select txid from payments.payments where status = 'FAILED'")
                .query(String.class)
                .single();
        assertThat(freshTxid).isNotEqualTo(exhaustedTxid);
    }

    // --------------------------------------------- 4. FAIL-OPEN (the S2 contract)

    @Test
    @Order(99)
    void redis_stopped_mid_test_replay_falls_back_to_db_and_stays_byte_equal() throws Exception {
        String idemKey = "idem-cache-failopen-01";

        var first = post("/v1/payments", body("{\"amount\":5000}"), authHeaders(idemKey, "req-cache-fo-01"));
        assertThat(first.statusCode()).isEqualTo(201);

        // warm the cache: one replay served by Redis
        var warm = post("/v1/payments", body("{\"amount\":5000}"), authHeaders(idemKey, "req-cache-fo-02"));
        assertThat(warm.statusCode()).isEqualTo(201);
        assertThat(warm.headers().firstValue("Idempotent-Replay")).contains("true");

        // KILL the cache mid-test — the fail-open proof must be a real Redis death, not a mock
        redis.stop();

        double failOpenBefore = counter("dargent.cache.failopen");
        long rowsBefore = rowCounts();

        var fallback = post("/v1/payments", body("{\"amount\":5000}"), authHeaders(idemKey, "req-cache-fo-03"));

        // correctness unchanged: DB fallback, byte-equal snapshot, zero side effects
        assertThat(fallback.statusCode()).isEqualTo(201);
        assertThat(fallback.headers().firstValue("Idempotent-Replay")).contains("true");
        assertThat(fallback.body()).isEqualTo(first.body());
        assertThat(rowCounts()).isEqualTo(rowsBefore);
        // the failure was absorbed and counted, never surfaced to the money path
        assertThat(counter("dargent.cache.failopen")).isGreaterThan(failOpenBefore);
    }

    // ------------------------------------------------------------------ helpers

    private double counter(String name) {
        var search = metrics.find(name).counter();
        return search == null ? 0.0 : search.count();
    }

    private String cacheKey(String idemKey) {
        return "dargent:idempotency-replay:" + MERCHANT + ":" + ENDPOINT + ":" + idemKey;
    }

    private JsonNode parse(HttpResponse<String> resp) throws Exception {
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
        var m = new LinkedHashMap<String, String>();
        m.put("Authorization", "Bearer " + rawKey);
        m.put("Content-Type", "application/json");
        m.put("Idempotency-Key", idemKey);
        m.put("X-Request-Id", requestId);
        return m;
    }

    private String body(String json) {
        return json;
    }

    private long paymentCount() {
        return jdbc.sql("select count(*) from payments.payments")
                .query(Long.class)
                .single();
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
        return payments + outbox + audit;
    }

    /** Configures the full context: Flyway, a fixed clock, and the PIX PSP stub. */
    @Configuration
    static class ReplayCacheTestConfig {

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
        PixStub pixStub() {
            return PIX_STUB;
        }
    }

    /**
     * PIX-profile PSP stub ({@code POST /cobs}). Its address is fed to the whole configured context
     * (including the real {@code pixRail}) via {@code dargent.psp.base-url} — production wiring,
     * pointed at an in-JVM stub (same pattern as CardPaymentIT).
     */
    static final PixStub PIX_STUB = new PixStub();

    static final HttpServer PSP_SERVER = startPspServer();

    private static HttpServer startPspServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/cobs", PIX_STUB::handle);
            server.createContext("/card-charges", c -> c.sendResponseHeaders(404, -1));
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
    static void stopPspServer() {
        PSP_SERVER.stop(0);
    }

    /** Success/fail stateful handler for the PIX cob create path. */
    static final class PixStub {
        enum Mode {
            SUCCESS,
            FAIL
        }

        volatile Mode mode = Mode.SUCCESS;

        void reset() {
            mode = Mode.SUCCESS;
        }

        void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] respBody;
            int status;
            if ("POST".equals(method) && "/cobs".equals(path)) {
                if (mode == Mode.FAIL) {
                    status = 500;
                    respBody = "{\"error\":\"internal\"}".getBytes(StandardCharsets.UTF_8);
                } else {
                    String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String txid = extractTxid(requestBody);
                    status = 200;
                    respBody = ("{\"txid\":\"" + txid + "\",\"expiresAt\":\"" + PSP_EXPIRES_AT
                                    + "\",\"endToEndId\":\"E2E-1\",\"brcode\":\"000201-terribly-long-brcode\"}")
                            .getBytes(StandardCharsets.UTF_8);
                }
            } else {
                status = 404;
                respBody = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, respBody.length);
            exchange.getResponseBody().write(respBody);
            exchange.close();
        }

        private static String extractTxid(String body) {
            int i = body.indexOf("\"txid\"");
            int start = body.indexOf("\"", i + 7) + 1;
            int end = body.indexOf("\"", start);
            return body.substring(start, end);
        }
    }
}
