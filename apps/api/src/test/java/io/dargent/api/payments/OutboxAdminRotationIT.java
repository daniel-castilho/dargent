package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;

/**
 * E9 §6.2 / Q11 rotation-window leg. Under the committed one-active-key-per-prefix schema
 * ({@code uq_api_keys_key_prefix_active WHERE revoked_at IS NULL}), key rotation is necessarily
 * revoke-then-provision. While {@code DARGENT_OUTBOX_ADMIN_KEY} still designates a REVOKED
 * predecessor, an operator presenting the ACTIVE successor authenticates as a real merchant yet is
 * forbidden → 403 fail-closed (a guaranteed operational state, not a hypothetical one). Presenting
 * the revoked predecessor itself → 401 — the env match never bypasses validation (validation first).
 * Both assertions share one context: admin env = revoked predecessor raw.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, OutboxAdminRotationIT.RotationTestConfig.class},
    properties = {
        "dargent.relay.enabled=true",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class OutboxAdminRotationIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);

    /** The log-lived, now-revoked predecessor — still the designated admin key (stale rotation). */
    private static final String PREV_RAW_KEY = ApiKeyHasher.generateRawKey();
    /** The freshly provisioned active successor. */
    private static final String SUCC_RAW_KEY = ApiKeyHasher.generateRawKey();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final LocalStackContainer localstack =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8.1"))
                    .withServices(LocalStackContainer.Service.SNS, LocalStackContainer.Service.SQS);

    @Autowired
    JdbcClient jdbc;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    @org.springframework.test.context.DynamicPropertySource
    static void env(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("AWS_ENDPOINT_URL", () -> localstack
                .getEndpointOverride(LocalStackContainer.Service.SNS).toString());
        registry.add("AWS_REGION", () -> "us-east-1");
        registry.add("AWS_ACCESS_KEY_ID", () -> "test");
        registry.add("AWS_SECRET_ACCESS_KEY", () -> "test");
        registry.add("DARGENT_EVENTS_TOPIC_ARN", () -> "arn:aws:sns:us-east-1:000000000000:unused");
        registry.add("DARGENT_EVENTS_PUBLISH_TIMEOUT_MS", () -> "2000");
        registry.add("DARGENT_RELAY_BATCH", () -> "32");
        registry.add("DARGENT_RELAY_WORKERS", () -> "2");
        registry.add("DARGENT_RELAY_POLL_MS", () -> "600000");
        registry.add("DARGENT_OUTBOX_RETENTION_DAYS", () -> "7");
        registry.add("DARGENT_OUTBOX_ADMIN_KEY", () -> PREV_RAW_KEY);
        registry.add("DARGENT_RELAY_MAX_ATTEMPTS", () -> "3");
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate payments.outbox, payments.audit_log, payments.api_keys "
                + "restart identity cascade").update();
        // The rotation state: the predecessor R is revoked, the successor M is the only active key.
        insertKey("33333333-3333-3333-3333-333333333333", PREV_RAW_KEY, "2027-01-01T11:00:00Z");
        insertKey("44444444-4444-4444-4444-444444444444", SUCC_RAW_KEY, null);
    }

    @Test
    void active_successor_is_403_while_admin_env_designates_the_revoked_predecessor() throws Exception {
        UUID rowId = UUID.randomUUID();
        seedExhausted(rowId);
        long auditsBefore = auditCount();

        var resp = requeue(rowId, SUCC_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(403);
        // forbidden attempts leave no audit mutation
        assertThat(auditCount()).isEqualTo(auditsBefore);
        // row untouched: still EXHAUSTED
        assertThat(status(rowId)).isEqualTo("EXHAUSTED");
    }

    @Test
    void revoked_predecessor_is_401_even_though_it_is_the_designated_admin_key() throws Exception {
        UUID rowId = UUID.randomUUID();
        seedExhausted(rowId);
        long auditsBefore = auditCount();

        var resp = requeue(rowId, PREV_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(auditCount()).isEqualTo(auditsBefore);
        assertThat(status(rowId)).isEqualTo("EXHAUSTED");
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> requeue(UUID id, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/outbox/" + id + "/requeue"))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "req-" + id)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void seedExhausted(UUID id) {
        jdbc.sql("""
                insert into payments.outbox (id, aggregate_id, type, version, payload, request_id, status, attempt_count, next_attempt_at)
                values (:id, :agg, :type, 1, :payload::jsonb, :req, 'EXHAUSTED', 3, '2027-01-01T11:00:00Z')
                """)
                .param("id", id)
                .param("agg", txid(id))
                .param("type", "payment.created")
                .param("payload", envelope(id))
                .param("req", "req-exh-" + id)
                .update();
    }

    private void insertKey(String id, String rawKey, String revokedAt) {
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), :revoked)")
                .param("id", UUID.fromString(id))
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .param("revoked", revokedAt == null ? null : Timestamp.from(Instant.parse(revokedAt)))
                .update();
    }

    private String status(UUID id) {
        return jdbc.sql("select status from payments.outbox where id = :id")
                .param("id", id).query(String.class).single();
    }

    private long auditCount() {
        return jdbc.sql("select count(*) from payments.audit_log").query(Long.class).single();
    }

    private static String txid(UUID id) {
        String hex = id.toString().replace("-", "");
        return "TXID" + hex.substring(0, 21).toUpperCase();
    }

    private static String envelope(UUID id) {
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("txid", txid(id));
            payload.put("merchantId", MERCHANT.toString());
            payload.put("amount", 10_000);
            payload.put("description", "requeue-rotation-it");
            Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("type", "payment.created");
            envelope.put("version", 1);
            envelope.put("aggregateId", txid(id));
            envelope.put("merchantId", MERCHANT.toString());
            envelope.put("requestId", "req-" + id);
            envelope.put("occurredAt", "2027-01-01T10:00:00Z");
            envelope.put("payload", payload);
            return new tools.jackson.databind.json.JsonMapper().writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @TestConfiguration
    static class RotationTestConfig {
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
    }
}