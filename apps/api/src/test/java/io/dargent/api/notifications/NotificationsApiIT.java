package io.dargent.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import java.time.Instant;
import java.util.Map;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * S6 — E10 spec §8.3 NotificationsApiIT.
 * Seeded rows → GET /v1/notifications shaped 200; pagination walk (cursor round-trip); 400 on bad
 * params; auth negative (401 without key). Tenant is the authenticated principal (AGENTS §3.7);
 * merchant_id is never a query param and never appears in the response body.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, NotificationsApiIT.ApiTestConfig.class},
    properties = {
        "dargent.relay.enabled=false",
        "dargent.notifs.consumer.enabled=false",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class NotificationsApiIT {

    private static final String REGION = "us-east-1";
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_MERCHANT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_KEY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant BASE = Instant.parse("2027-01-01T12:00:00Z");
    private static final JsonMapper MAPPER = new JsonMapper();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate notifications.notification, payments.api_keys restart identity cascade").update();
        provisionKey(KEY_ID, MERCHANT, rawKey);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void seeded_rows_return_shaped_200_list_without_payload() throws Exception {
        seed("payment.confirmed", "tx-1", BASE.plusSeconds(3));
        seed("payment.confirmed", "tx-2", BASE.plusSeconds(2));
        seed("payment.rejected", "tx-3", BASE.plusSeconds(1));

        var resp = get("/v1/notifications", bearer());
        assertThat(resp.statusCode()).as("body=" + resp.body()).isEqualTo(200);

        JsonNode body = MAPPER.readTree(resp.body());
        JsonNode data = body.get("data");
        assertThat(data).hasSize(3);
        // ordered created_at desc
        assertThat(data.get(0).get("txid").asText()).isEqualTo("tx-1");
        assertThat(data.get(1).get("txid").asText()).isEqualTo("tx-2");
        assertThat(data.get(2).get("txid").asText()).isEqualTo("tx-3");
        // required fields present; merchantId echoes the tenant (TD-17 output echo); payload absent
        // naming guard negatives (TD-18): snake_case keys never emitted
        assertThat(data.get(0).has("id")).isTrue();
        assertThat(data.get(0).has("eventId")).isTrue();
        assertThat(data.get(0).get("type").asText()).isEqualTo("payment.confirmed");
        assertThat(data.get(0).get("merchantId").asText()).isEqualTo(MERCHANT.toString());
        assertThat(data.get(0).has("occurredAt")).isTrue();
        assertThat(data.get(0).has("createdAt")).isTrue();
        assertThat(data.get(0).has("payload")).isFalse();
        assertThat(data.get(0).has("event_id")).isFalse();
        assertThat(data.get(0).has("occurred_at")).isFalse();
        assertThat(data.get(0).has("created_at")).isFalse();
        assertThat(body.has("next_cursor")).isFalse();
    }

    @Test
    void type_filter_returns_only_matching_rows() throws Exception {
        seed("payment.confirmed", "tx-1", BASE.plusSeconds(3));
        seed("payment.rejected", "tx-2", BASE.plusSeconds(2));

        var resp = get("/v1/notifications?type=payment.rejected", bearer());
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(resp.body()).get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("type").asText()).isEqualTo("payment.rejected");
        assertThat(data.get(0).get("txid").asText()).isEqualTo("tx-2");
    }

    @Test
    void pagination_walks_cursor_round_trip() throws Exception {
        // 5 rows, limit 3 → first page returns 3 + a cursor; second page returns 2 + null cursor.
        for (int i = 5; i >= 1; i--) {
            seed("payment.confirmed", "tx-" + i, BASE.plusSeconds(i));
        }

        var page1 = get("/v1/notifications?limit=3", bearer());
        assertThat(page1.statusCode()).isEqualTo(200);
        JsonNode b1 = MAPPER.readTree(page1.body());
        assertThat(b1.get("data")).hasSize(3);
        assertThat(b1.get("data").get(0).get("txid").asText()).isEqualTo("tx-5");
        assertThat(b1.get("data").get(1).get("txid").asText()).isEqualTo("tx-4");
        assertThat(b1.get("data").get(2).get("txid").asText()).isEqualTo("tx-3");
        String next = b1.get("nextCursor").asText();
        assertThat(next).isNotBlank();

        var page2 = get("/v1/notifications?limit=3&cursor=" + next, bearer());
        assertThat(page2.statusCode()).isEqualTo(200);
        JsonNode b2 = MAPPER.readTree(page2.body());
        assertThat(b2.get("data")).hasSize(2);
        assertThat(b2.get("data").get(0).get("txid").asText()).isEqualTo("tx-2");
        assertThat(b2.get("data").get(1).get("txid").asText()).isEqualTo("tx-1");
        assertThat(b2.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void bad_limit_and_bad_cursor_return_400() throws Exception {
        seed("payment.confirmed", "tx-1", BASE.plusSeconds(1));

        var badLimit = get("/v1/notifications?limit=0", bearer());
        assertThat(badLimit.statusCode()).isEqualTo(400);

        var badCursor = get("/v1/notifications?cursor=!!!not-base64!!!", bearer());
        assertThat(badCursor.statusCode()).isEqualTo(400);
    }

    @Test
    void missing_api_key_returns_401() throws Exception {
        var resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/notifications"))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void cross_tenant_rows_are_never_visible_across_credentials() throws Exception {
        // Both merchants' rows coexists in the DB throughout. uq_api_keys_key_prefix_active is a
        // partial unique index over active (non-revoked) keys sharing the fixed dev prefix
        // (psp_test_), so only ONE key can be active at a time — the payments IT cross-tenant
        // pattern: prove direction A with the principal key active, then revoke it and prove
        // direction B with the second merchant's key active. Both directions therefore assert that
        // a credential NEVER sees the other tenant's rows that are still present in the DB.

        // Phase A — principal key active (from setUp); both merchants' rows exist.
        seed("payment.confirmed", "principal-tx", BASE.plusSeconds(2), MERCHANT);
        seed("payment.confirmed", "other-tx", BASE.plusSeconds(3), OTHER_MERCHANT);

        var principalResp = get("/v1/notifications", bearer());
        assertThat(principalResp.statusCode()).isEqualTo(200);
        JsonNode principalData = MAPPER.readTree(principalResp.body()).get("data");
        assertThat(principalData).hasSize(1);
        assertThat(principalData.get(0).get("txid").asText()).isEqualTo("principal-tx");
        assertThat(principalData.get(0).get("merchantId").asText()).isEqualTo(MERCHANT.toString());

        // Phase B — revoke principal key, activate second merchant's key; principal's row still present.
        jdbc.sql("update payments.api_keys set revoked_at = now() where id = :id")
                .param("id", KEY_ID).update();
        String otherRawKey = ApiKeyHasher.generateRawKey();
        provisionKey(OTHER_KEY_ID, OTHER_MERCHANT, otherRawKey);

        var otherResp = get("/v1/notifications", bearer(otherRawKey));
        assertThat(otherResp.statusCode()).isEqualTo(200);
        JsonNode otherData = MAPPER.readTree(otherResp.body()).get("data");
        assertThat(otherData).hasSize(1);
        assertThat(otherData.get(0).get("txid").asText()).isEqualTo("other-tx");
        assertThat(otherData.get(0).get("merchantId").asText()).isEqualTo(OTHER_MERCHANT.toString());
    }

    // ------------------------------------------------------------------ helpers

    private long seed(String type, String txid, Instant createdAt) {
        return seed(type, txid, createdAt, MERCHANT);
    }

    private long seed(String type, String txid, Instant createdAt, UUID merchantId) {
        return jdbc.sql("""
                insert into notifications.notification
                    (id, event_id, type, txid, merchant_id, payload, occurred_at, created_at)
                values (:id, :eventId, :type, :txid, :merchant, :payload::jsonb, :occurred, :created)
                """)
                .param("id", UUID.randomUUID())
                .param("eventId", UUID.randomUUID())
                .param("type", type)
                .param("txid", txid)
                .param("merchant", merchantId)
                .param("payload", "{\"amount\":10000}")
                .param("occurred", java.sql.Timestamp.from(createdAt))
                .param("created", java.sql.Timestamp.from(createdAt))
                .update();
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET();
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> bearer() {
        return Map.of("Authorization", "Bearer " + rawKey);
    }

    private Map<String, String> bearer(String key) {
        return Map.of("Authorization", "Bearer " + key);
    }

    private void provisionKey(UUID keyId, UUID merchantId, String key) {
        jdbc.sql("""
                insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at)
                values (:id, :merchant, 'api-it-key', :prefix, :hash, now(), null)
                """)
                .param("id", keyId)
                .param("merchant", merchantId)
                .param("prefix", ApiKeyHasher.prefix(key))
                .param("hash", ApiKeyHasher.hash(key))
                .update();
    }

    @TestConfiguration
    static class ApiTestConfig {

        @Bean
        @Primary
        Flyway flyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas("payments", "ledger", "notifications")
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
    }
}
