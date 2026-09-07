package io.dargent.api.ledger;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * E13 R3 ledger-admin ladder over the real HTTP surface (mirrors {@code OutboxAdminRotationIT}):
 * the three admin-gated endpoints ({@code /v1/ledger/rebuild}, {@code /v1/ledger/proof},
 * {@code /v1/ledger/settlements}) under the committed one-active-key-per-prefix rotation state.
 *
 * <p>Context: {@code DARGENT_LEDGER_ADMIN_KEY} designates the REVOKED predecessor while the ACTIVE
 * successor is the only valid key. Ladder legs: (a) presenting the ACTIVE successor — a real,
 * validated merchant identity that is not the designated admin — → 403 fail-closed, no audit
 * mutation, no ledger mutation; (b) presenting the revoked predecessor itself → 401 — the env match
 * never bypasses validation (validation first, filter); (c) the designated admin key, valid and
 * active → 200 with {@code ledger_admin_*} audit rows carrying the REAL key id, never a sentinel.
 * The 404-hidden leg (unset env) lives in {@code LedgerAdminHiddenIT} — a property flip needs its
 * own context.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {DargentApiApplication.class, LedgerAdminRotationIT.RotationTestConfig.class},
        properties = {"dargent.relay.enabled=false", "dargent.psp.webhook-secret=dev-only-secret"})
@Testcontainers
class LedgerAdminRotationIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper MAPPER = new JsonMapper();

    /** The log-lived, now-revoked predecessor — still the designated ledger-admin key. */
    private static final String PREV_RAW_KEY = ApiKeyHasher.generateRawKey();
    /** The freshly provisioned active successor (a real merchant key, not the admin). */
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

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) {
        registry.add("DARGENT_LEDGER_ADMIN_KEY", () -> PREV_RAW_KEY);
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate ledger.events, ledger.postings, ledger.journal_entries, ledger.balances, "
                        + "ledger.settlements, ledger.audit_log, payments.api_keys restart identity cascade")
                .update();
        // The rotation state: the predecessor R is revoked, the successor M is the only active key.
        insertKey("33333333-3333-3333-3333-333333333333", PREV_RAW_KEY, "2027-01-01T11:00:00Z");
        insertKey("44444444-4444-4444-4444-444444444444", SUCC_RAW_KEY, null);
    }

    @Test
    void active_successor_is_403_on_all_three_admin_endpoints_and_mutates_nothing() throws Exception {
        long auditsBefore = auditCount();

        assertThat(proof(SUCC_RAW_KEY).statusCode()).isEqualTo(403);
        assertThat(rebuild(SUCC_RAW_KEY).statusCode()).isEqualTo(403);
        assertThat(settle(SUCC_RAW_KEY, "idem-admin-403-rotation").statusCode()).isEqualTo(403);

        // 403 fail-closed: no audit mutation, no settlement, no journal.
        assertThat(auditCount()).isEqualTo(auditsBefore);
        assertThat(settlementCount()).isZero();
        assertThat(journalCount()).isZero();
    }

    @Test
    void revoked_predecessor_is_401_even_though_it_is_the_designated_admin_key() throws Exception {
        long auditsBefore = auditCount();

        assertThat(proof(PREV_RAW_KEY).statusCode()).isEqualTo(401);
        assertThat(rebuild(PREV_RAW_KEY).statusCode()).isEqualTo(401);
        assertThat(settle(PREV_RAW_KEY, "idem-admin-401-rotation").statusCode()).isEqualTo(401);

        assertThat(auditCount()).isEqualTo(auditsBefore);
        assertThat(settlementCount()).isZero();
    }

    @Test
    void designated_admin_key_valid_and_active_is_200_with_ledger_admin_audit_rows() throws Exception {
        // Rotation contract (uq_api_keys_key_prefix_active, one active key per prefix): revoke
        // the successor and re-validate the predecessor as the sole ACTIVE key — the env still
        // designates it; a designated key must ALSO be valid+active to pass (validation first).
        jdbc.sql("delete from payments.api_keys where key_hash in (:p, :s)")
                .param("p", ApiKeyHasher.hash(PREV_RAW_KEY))
                .param("s", ApiKeyHasher.hash(SUCC_RAW_KEY))
                .update();
        insertKey("55555555-5555-5555-5555-555555555555", PREV_RAW_KEY, null);

        var proof = proof(PREV_RAW_KEY);
        assertThat(proof.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(proof.body()).path("ok").asBoolean()).isTrue();

        var rebuild = rebuild(PREV_RAW_KEY);
        assertThat(rebuild.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(rebuild.body()).path("ok").asBoolean()).isTrue();

        // Audit rows carry the presented key's REAL identity (key id 5555…, never a sentinel).
        long adminProofAudits = jdbc.sql(
                        "select count(*) from ledger.audit_log where command = 'ledger_admin_proof' and actor_key = :k")
                .param("k", UUID.fromString("55555555-5555-5555-5555-555555555555"))
                .query(Long.class)
                .single();
        long adminRebuildAudits = jdbc.sql(
                        "select count(*) from ledger.audit_log where command = 'ledger_admin_rebuild' and actor_key = :k")
                .param("k", UUID.fromString("55555555-5555-5555-5555-555555555555"))
                .query(Long.class)
                .single();
        assertThat(adminProofAudits).isEqualTo(1);
        assertThat(adminRebuildAudits).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> proof(String bearer) throws Exception {
        return send("GET", "/v1/ledger/proof", bearer, null);
    }

    private HttpResponse<String> rebuild(String bearer) throws Exception {
        return send("POST", "/v1/ledger/rebuild", bearer, null);
    }

    private HttpResponse<String> settle(String bearer, String idemKey) throws Exception {
        return send("POST", "/v1/ledger/settlements", bearer, idemKey);
    }

    private HttpResponse<String> send(String method, String path, String bearer, String idemKey) throws Exception {
        var builder =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).header("Content-Type", "application/json");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (idemKey != null) {
            builder.header("Idempotency-Key", idemKey);
        }
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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

    private long auditCount() {
        return jdbc.sql("select count(*) from ledger.audit_log")
                .query(Long.class)
                .single();
    }

    private long settlementCount() {
        return jdbc.sql("select count(*) from ledger.settlements")
                .query(Long.class)
                .single();
    }

    private long journalCount() {
        return jdbc.sql("select count(*) from ledger.journal_entries")
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
        Clock fixedClock() {
            return FIXED_CLOCK;
        }
    }
}
