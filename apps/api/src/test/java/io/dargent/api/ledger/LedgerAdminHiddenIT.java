package io.dargent.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * E13 R3 — the DEFAULT-EMPTY contract leg: {@code DARGENT_LEDGER_ADMIN_KEY} unset → the three
 * admin endpoints ({@code /v1/ledger/rebuild}, {@code /v1/ledger/proof},
 * {@code /v1/ledger/settlements}) are 404-HIDDEN. Even a valid, active merchant key that would
 * pass the filter sees "Unknown route" — the default is the contract (never change without owner
 * sign-off). The balance read (plain merchant surface) stays reachable in the same context —
 * the gate is endpoint-scoped, not module-scoped.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {DargentApiApplication.class, LedgerAdminHiddenIT.HiddenTestConfig.class},
        properties = {
            "dargent.relay.enabled=false",
            "dargent.psp.webhook-secret=dev-only-secret",
            // Deterministic unset-leg: pin EMPTY explicitly (resists ambient CI env leakage).
            "DARGENT_LEDGER_ADMIN_KEY="
        })
@Testcontainers
class LedgerAdminHiddenIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper MAPPER = new JsonMapper();

    private final String rawKey = ApiKeyHasher.generateRawKey();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate ledger.events, ledger.postings, ledger.journal_entries, ledger.balances, "
                        + "ledger.settlements, ledger.audit_log, payments.api_keys restart identity cascade")
                .update();
        jdbc.sql("insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
        // The balance read targets an account that must exist (LedgerAccountNotFoundException → 404
        // otherwise, which would conflate "hidden admin" with "missing account").
        jdbc.sql("insert into ledger.balances (account, balance_cents) values (:a, :c)")
                .param("a", "merchant:" + MERCHANT + ":available")
                .param("c", 5000L)
                .update();
    }

    @Test
    void admin_endpoints_are_404_hidden_when_env_unset_but_balance_stays_reachable() throws Exception {
        assertThat(proof().statusCode()).isEqualTo(404);
        assertThat(rebuild().statusCode()).isEqualTo(404);
        assertThat(settle().statusCode()).isEqualTo(404);

        // The audit trail stays untouched — hidden ≠ audited.
        assertThat(auditCount()).isZero();

        // Balance (plain authenticated merchant read) is unaffected by the admin gate.
        var balance = http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/ledger/accounts/merchant:" + MERCHANT + ":available/balance"))
                        .header("Authorization", "Bearer " + rawKey)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(balance.statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> proof() throws Exception {
        return send("GET", "/v1/ledger/proof");
    }

    private HttpResponse<String> rebuild() throws Exception {
        return send("POST", "/v1/ledger/rebuild");
    }

    private HttpResponse<String> settle() throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/ledger/settlements"))
                .header("Authorization", "Bearer " + rawKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "idem-hidden-01")
                .POST(HttpRequest.BodyPublishers.noBody());
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + rawKey)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long auditCount() {
        return jdbc.sql("select count(*) from ledger.audit_log")
                .query(Long.class)
                .single();
    }

    @TestConfiguration
    static class HiddenTestConfig {

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
