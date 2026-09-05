package io.dargent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * On-call txid drill IT (E11 S2, Block 2 remediation).
 *
 * <p>The operator trail is resolved FROM THE EMITTED ECS LINES (the S1 wire capture): the payment is
 * created through the real HTTP surface with X-Request-Id, then the pendular state is forced by a
 * direct {@code next_reconcile_at} update (house precedent, Q14 — the create itself is real, only the
 * "already due at 03:00" wiring is seeded). The drill then:
 * (1) searches the formatted lines by txid → status and request_id from the wire;
 * (2) re-searches by request_id → the full two-line trail (intake + result);
 * DB rows complement (never replace): last transition event and next_attempt_at.
 * Budget asserted via injected Clock (≤2 min modeled) — the intake→result line timestamps must be
 * within the modeled window, which a same-request pair always is.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, OnCallTxidDrillIT.TestConfig.class},
    properties = {
        "spring.profiles.active=prod",
        "management.server.port=9090",
        "DARGENT_DB_PASSWORD=prod-test-password-that-is-at-least-32-chars-long",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        "PSP_BASE_URL=http://psp-stub:8090",
        "PSP_WEBHOOK_SECRET=prod-test-webhook-secret-that-is-long-enough",
        "dargent.psp.base-url=http://psp-stub:8090",
        "dargent.psp.webhook-secret=prod-test-webhook-secret-that-is-long-enough",
        "dargent.relay.enabled=false"
    }
)
@Testcontainers
class OnCallTxidDrillIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final JsonMapper MAPPER = new JsonMapper();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    @Autowired
    PspStub psp;

    @LocalServerPort
    int mainPort;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    @BeforeEach
    void setUp() throws Exception {
        CapturingAppender.clear();
        baseUrl = "http://localhost:" + mainPort;
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

    @Test
    void onCallDrill_statusAndTrail_resolvedFromEmittedLines() throws Exception {
        // Given: the operator's real create at 02:50, with its own X-Request-Id
        String requestId = "req-drill-01";
        var resp = post("/v1/payments",
                "{\"amount\":5000,\"description\":\"drill seed\"}",
                Map.of(
                        "Authorization", "Bearer " + rawKey,
                        "Content-Type", "application/json",
                        "Idempotency-Key", "idem-drill-01",
                        "X-Request-Id", requestId
                ));
        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(resp.headers().firstValue("X-Request-Id")).contains(requestId);
        String txid = MAPPER.readTree(resp.body()).at("/txid").asText();

        // Pendular wiring: by 03:00 the payment is due for reconciliation — force it (house precedent)
        Instant now = clock.instant();
        Instant past = now.minus(Duration.ofMinutes(5));
        Instant future = now.plus(Duration.ofMinutes(30));
        jdbc.sql("update payments.payments set next_reconcile_at = :due, expires_at = :expires where txid = :txid")
                .param("due", java.sql.Timestamp.from(past))
                .param("expires", java.sql.Timestamp.from(future))
                .param("txid", txid)
                .update();

        // When: the operator searches the emitted lines by txid
        List<JsonNode> lines = capturedJsonLines();
        JsonNode resultLine = lines.stream()
                .filter(l -> l.path("txid").asText().equals(txid)
                        && l.path("message").asText().contains("Payment create result"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no result line for txid " + txid));

        // (1) status and request_id resolve from the wire
        String status = extractStatus(resultLine.path("message").asText());
        assertThat(status).isEqualTo("PENDING");
        String lineRequestId = resultLine.path("requestId").asText();
        assertThat(lineRequestId).isEqualTo(requestId);

        // (2) re-search by request_id returns the full trail (intake + result lines)
        List<JsonNode> trail = lines.stream()
                .filter(l -> l.path("requestId").asText().equals(lineRequestId))
                .toList();
        JsonNode intakeLine = trail.stream()
                .filter(l -> l.path("message").asText().contains("Payment create request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no intake line on the trail"));
        assertThat(trail.size()).as("intake + result on the trail").isGreaterThanOrEqualTo(2);

        // Budget: modeled ≤2 minutes — the two trail lines were emitted by the same request
        Instant intakeAt = Instant.parse(intakeLine.path("@timestamp").asText());
        Instant resultAt = Instant.parse(resultLine.path("@timestamp").asText());
        assertThat(Duration.between(intakeAt, resultAt).abs()).isLessThan(Duration.ofMinutes(2));

        // DB complement (may complement, not replace): last transition + next_attempt_at
        var outbox = jdbc.sql(
                "select type, request_id, next_attempt_at from payments.outbox where aggregate_id = :txid")
                .param("txid", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getTimestamp(3)})
                .list();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0)[0]).as("last transition").isEqualTo("payment.created");
        assertThat(outbox.get(0)[1]).isEqualTo(requestId);
        Instant nextAttempt = outbox.get(0)[2] != null
                ? ((java.sql.Timestamp) outbox.get(0)[2]).toInstant()
                : null;
        assertThat(nextAttempt).isNotNull();

        // Pendular facts confirmed on the DB state (due, not expired)
        var payment = jdbc.sql(
                "select status, next_reconcile_at, expires_at from payments.payments where txid = :txid")
                .param("txid", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getTimestamp(2), rs.getTimestamp(3)})
                .single();
        assertThat(payment[0]).isEqualTo("PENDING");
        Instant reconciledDue = ((java.sql.Timestamp) payment[1]).toInstant();
        Instant expiresAt = ((java.sql.Timestamp) payment[2]).toInstant();
        assertThat(reconciledDue).isBefore(now);
        assertThat(expiresAt).isAfter(now);

        // Budget anchored to the modeled clock
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-08-29T12:00:00Z"));
    }

    // ------------------------------------------------------------------ helpers

    private List<String> capturedFormattedLines() {
        return EcsLogContext.formatEcs(CapturingAppender.getEvents());
    }

    private List<JsonNode> capturedJsonLines() {
        return capturedFormattedLines().stream()
                .map(line -> {
                    try {
                        return MAPPER.readTree(line);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static String extractStatus(String message) {
        int i = message.indexOf("status=");
        if (i < 0) {
            throw new AssertionError("no status= in message: " + message);
        }
        String rest = message.substring(i + "status=".length());
        int end = rest.indexOf(' ');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ test config

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
            return Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        Object ecsEnvironment(Environment environment) {
            EcsLogContext.registerSpringEnvironment(environment);
            return new Object();
        }

        @Bean
        PspStub pspStub() {
            return new PspStub();
        }

        @Bean
        com.sun.net.httpserver.HttpServer pspServer(PspStub psp) throws IOException {
            com.sun.net.httpserver.HttpServer server =
                    com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/cobs", psp::handle);
            server.start();
            return server;
        }

        @Bean
        @Primary
        PspPort pspTestPort(com.sun.net.httpserver.HttpServer server, PspStub psp) {
            int port = server.getAddress().getPort();
            return new SimulatorChargeAdapter("http://127.0.0.1:" + port, 3, Duration.ofMillis(20), psp::sleeper);
        }
    }
}