package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M5 S4 ladder leg for the webhook reprocess admin surface (mirror of OutboxAdminRotationIT).
 * Under the committed one-active-key-per-prefix schema, key rotation is necessarily revoke-then-
 * provision. While {@code DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY} still designates a REVOKED
 * predecessor, an operator presenting the ACTIVE successor authenticates as a real merchant yet is
 * forbidden → 403 fail-closed (a guaranteed operational state, not a hypothetical one). Presenting
 * the revoked predecessor itself → 401 — the env match never bypasses validation (validation first).
 * Both assertions share one context: admin env = revoked predecessor raw; the row stays RECEIVED
 * with no audit mutation.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {DargentApiApplication.class, WebhookReprocessAdminRotationIT.RotationTestConfig.class},
        properties = "dargent.psp.webhook-secret=dev-only-secret")
@Testcontainers
class WebhookReprocessAdminRotationIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PROVIDER_EVENT_ID = "E9ROTATION000000000000000000000X|payment.confirmed";
    /** Payments txid is varchar(25): "TXID" + 21 chars — the audit aggregate must fit. */
    private static final String ROW_TXID = "TXID" + "ABCDEF0123456789ABCDE";

    /** The long-lived, now-revoked predecessor — still the designated admin key (stale rotation). */
    private static final String PREV_RAW_KEY = ApiKeyHasher.generateRawKey();
    /** The freshly provisioned active successor. */
    private static final String SUCC_RAW_KEY = ApiKeyHasher.generateRawKey();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    @org.springframework.test.context.DynamicPropertySource
    static void env(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("DARGENT_WEBHOOK_REPROCESS_ADMIN_KEY", () -> PREV_RAW_KEY);
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate payments.webhook_events, payments.audit_log, payments.api_keys "
                        + "restart identity cascade")
                .update();
        // The rotation state: the predecessor R is revoked, the successor M is the only active key.
        insertKey("33333333-3333-3333-3333-333333333333", PREV_RAW_KEY, "2026-01-01T11:00:00Z");
        insertKey("44444444-4444-4444-4444-444444444444", SUCC_RAW_KEY, null);
    }

    @Test
    void active_successor_is_403_while_admin_env_designates_the_revoked_predecessor() throws Exception {
        seedRow();
        long auditsBefore = auditCount();

        var resp = reprocess(PROVIDER_EVENT_ID, SUCC_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(403);
        // forbidden attempts leave no audit mutation
        assertThat(auditCount()).isEqualTo(auditsBefore);
        // row untouched: still RECEIVED
        assertThat(status()).isEqualTo("RECEIVED");
    }

    @Test
    void revoked_predecessor_is_401_even_though_it_is_the_designated_admin_key() throws Exception {
        seedRow();
        long auditsBefore = auditCount();

        var resp = reprocess(PROVIDER_EVENT_ID, PREV_RAW_KEY);

        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(auditCount()).isEqualTo(auditsBefore);
        assertThat(status()).isEqualTo("RECEIVED");
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> reprocess(String providerEventId, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/webhooks/reprocess"))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "req-" + providerEventId)
                .POST(HttpRequest.BodyPublishers.ofString("{\"providerEventId\":\"" + providerEventId + "\"}"));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void seedRow() {
        jdbc.sql("""
                insert into payments.webhook_events (id, provider_event_id, psp_event_id, type, txid,
                    payload_raw, signature_valid, status, received_at)
                values (:id, :p, 'psp-rot-it', :type, :txid, :body::jsonb, true, 'RECEIVED', '2026-01-01T10:00:00Z')
                """)
                .param("id", UUID.randomUUID())
                .param("p", PROVIDER_EVENT_ID)
                .param("type", "payment.confirmed")
                .param("txid", ROW_TXID)
                .param(
                        "body",
                        "{\"eventId\":\"psp-rot-it\",\"type\":\"payment.confirmed\",\"txid\":\""
                                + ROW_TXID + "\",\"endToEndId\":\"E9ROTATION000000000000000000000X\""
                                + ",\"amount\":10000,\"paidAt\":\"2026-01-01T10:00:01Z\"}")
                .update();
    }

    private void insertKey(String id, String rawKey, String revokedAt) {
        jdbc.sql("insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), :revoked)")
                .param("id", UUID.fromString(id))
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .param("revoked", revokedAt == null ? null : Timestamp.from(Instant.parse(revokedAt)))
                .update();
    }

    private String status() {
        return jdbc.sql("select status from payments.webhook_events where provider_event_id = :p")
                .param("p", PROVIDER_EVENT_ID)
                .query(String.class)
                .single();
    }

    private long auditCount() {
        return jdbc.sql("select count(*) from payments.audit_log")
                .query(Long.class)
                .single();
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
        java.time.Clock fixedClock() {
            return java.time.Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), java.time.ZoneOffset.UTC);
        }
    }
}
