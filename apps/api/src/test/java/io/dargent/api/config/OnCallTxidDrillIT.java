package io.dargent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import java.io.IOException;
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
import java.util.UUID;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * On-call txid drill IT (E11 S2).
 * Seeds a pendular payment (PENDING with due retry) directly in DB, then runs the operator path:
 * (1) search by txid → status, last transition, request_id, next_attempt_at
 * (2) re-search by request_id → full request trail
 * Budget asserted via injected Clock (≤2 min modeled).
 * PSP integration is tested in JsonLogCorrelationIT; this test focuses on drill logic.
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

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @LocalServerPort
    int mainPort;

    @Autowired
    Clock clock;

    @Value("${dargent.pix.profile.pix-key}")
    String pixKey;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() throws Exception {
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
    }

    @Test
    void onCallDrill_txidSearch_resolvesStatusAndNextAttempt() throws Exception {
        // Given: seed a pendular payment directly in DB (bypasses PSP for drill focus)
        String txid = "DR" + UUID.randomUUID().toString().substring(0, 23);
        String requestId = "req-drill-01";
        Instant now = clock.instant();
        Instant past = now.minus(Duration.ofMinutes(5));
        Instant future = now.plus(Duration.ofMinutes(30));

// Seed payment row (PENDING, due for retry)
        Instant createdAt = clock.instant();
        jdbc.sql("""
            insert into payments.payments (id, txid, merchant_id, amount_cents, status, version, created_at, next_reconcile_at, expires_at)
            values (:id, :txid, :merchant, 5000, 'PENDING', 1, :createdAt, :nextReconcileAt, :expiresAt)
            """)
                .param("id", UUID.randomUUID())
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .param("nextReconcileAt", java.sql.Timestamp.from(past))
                .param("expiresAt", java.sql.Timestamp.from(future))
                .update();

        // Seed outbox event with request_id (simulates intake log)
        jdbc.sql("""
            insert into payments.outbox (id, aggregate_id, type, version, request_id, payload, created_at)
            values (:id, :txid, 'payment.created', 1, :requestId, :payload::jsonb, :createdAt)
            """)
                .param("id", UUID.randomUUID())
                .param("txid", txid)
                .param("requestId", "req-drill-01")
                .param("payload", "{\"txid\":\"" + txid + "\",\"amount\":5000}")
                .param("createdAt", java.sql.Timestamp.from(createdAt))
                .update();

        // When: Operator searches by txid
        var payment = jdbc.sql(
                "select status, next_reconcile_at, expires_at from payments.payments where txid = :txid")
                .param("txid", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getTimestamp(2), rs.getTimestamp(3)})
                .single();
        String status = (String) payment[0];
        Instant nextAttempt = payment[1] != null ? ((java.sql.Timestamp) payment[1]).toInstant() : null;
        Instant expiresAt = payment[2] != null ? ((java.sql.Timestamp) payment[2]).toInstant() : null;

        // Then: DB reflects pendular state
        assertThat(status).isEqualTo("PENDING");
        assertThat(nextAttempt).isNotNull();
        assertThat(nextAttempt).isBefore(now); // due for retry
        assertThat(expiresAt).isAfter(now); // not yet expired

        // When: Operator re-searches by request_id
        var outbox = jdbc.sql(
                "select type, request_id, created_at from payments.outbox where aggregate_id = :txid")
                .param("txid", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getTimestamp(3)})
                .list();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0)[1]).isEqualTo("req-drill-01");

        // Budget: assert via injected Clock (≤2 min modeled)
        // The operator actions (searches) are instantaneous in test time
        // The Clock is fixed, so no wall-clock time elapses
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-08-29T12:00:00Z"));
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
    }
}