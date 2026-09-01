package io.dargent.api.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.ledger.application.EventIngestionUseCase;
import io.dargent.ledger.application.LedgerReconciliationUseCase;
import io.dargent.ledger.application.SettlementUseCase;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * IT5 (E7 §7): ledger settlement over the real HTTP surface against PG16 — fresh settle (201,
 * balanced postings: available → 0, payouts debited), idempotent replay (Idempotent-Replay header,
 * no double), and zero-balance → 409. IT5b is the concurrent settle+confirm race on the FOR UPDATE
 * balance row: both land, proof stays green, no lost update and never a negative available balance.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, LedgerSettlementIT.SettlementTestConfig.class},
    properties = {
        "dargent.relay.enabled=false",
        "dargent.ledger.consumer.enabled=false",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class LedgerSettlementIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper MAPPER = new JsonMapper();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EventIngestionUseCase ingestion;

    @Autowired
    SettlementUseCase settlement;

    @Autowired
    LedgerReconciliationUseCase reconciliation;

    @LocalServerPort
    int port;

    private String baseUrl;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String rawKey = ApiKeyHasher.generateRawKey();

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        jdbc.sql("truncate ledger.events, ledger.postings, ledger.journal_entries, ledger.balances, "
                + "ledger.settlements, ledger.audit_log, payments.api_keys restart identity cascade").update();
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'ledger-settle-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
    }

    /** IT5 — settle: 201 with balanced postings, idempotent replay, then 409 on zero balance. */
    @Test
    void settle_moves_available_to_payouts_then_replays_then_409_on_zero() throws Exception {
        fund(payment("p-1", 10000));

        var first = postSettlement("settle-key-01");
        assertThat(first.statusCode()).isEqualTo(201);
        JsonNode body = MAPPER.readTree(first.body());
        assertThat(body.at("/amountCents").asLong()).isEqualTo(9900);
        assertThat(first.headers().firstValue("Idempotent-Replay").isEmpty()).isTrue();

        // Balanced postings: available DEBIT 9900 → 0, payouts CREDIT 9900.
        assertThat(balance("merchant:" + MERCHANT + ":available")).isZero();
        assertThat(balance("payouts:external")).isEqualTo(9900);
        assertThat(settlementCount()).isEqualTo(1);
        assertProofOk();
        assertThat(auditCount("SETTLE")).isEqualTo(1);

        // Replay same key — same settlement, no double-posting.
        var replay = postSettlement("settle-key-01");
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replay")).hasValue("true");
        JsonNode replayBody = MAPPER.readTree(replay.body());
        assertThat(replayBody.at("/id").asText()).isEqualTo(body.at("/id").asText());
        assertThat(replayBody.at("/amountCents").asLong()).isEqualTo(9900);
        assertThat(balance("payouts:external")).isEqualTo(9900);
        assertThat(settlementCount()).isEqualTo(1);
        assertProofOk();

        // Second settle with a different key on a zero balance → 409 no_balance_to_settle.
        var empty = postSettlement("settle-key-02");
        assertThat(empty.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(empty.body()).path("code").asText()).isEqualTo("no_balance_to_settle");
        assertThat(settlementCount()).isEqualTo(1);
        assertProofOk();
    }

    /** IT5b — concurrent settle + confirm on the same balance → both land, proof green, no lost update. */
    @Test
    void concurrent_settle_and_confirm_serialize_on_for_update_without_lost_update() throws Exception {
        fund(payment("c-1", 5000)); // available = 4900 (net of 100 bps fee)

        long startingAvailable = available();
        long confirmNet = 3000; // confirm of amount 3100 fee 100

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        pool.submit(() -> {
            try {
                start.await();
                settlement.settle(MERCHANT, "race-settle-key", KEY_ID);
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                ingestion.processMessage(confirmedEnvelope("race-confirm-tx", 3100, 100));
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        long settled = jdbc.sql("select amount_cents from ledger.settlements where idempotency_key='race-settle-key'")
                .query(Long.class).optional().orElse(0L);
        long finalAvailable = available();

        // Both landed: a settlement exists and a confirm journal entry was posted.
        assertThat(settled).isGreaterThan(0);
        assertThat(journalEntries()).isEqualTo(3); // funded confirm + settle + race confirm
        assertProofOk();

        // The FOR UPDATE lock serializes the two: whichever runs first, the other recomputes,
        // so the available balance is neither negative nor wasteful — it equals the residual.
        long residual = startingAvailable + confirmNet - settled;
        assertThat(finalAvailable).isEqualTo(residual);
        assertThat(finalAvailable).isGreaterThanOrEqualTo(0);
        // No duplicate settlement for the key.
        assertThat(jdbc.sql("select count(*) from ledger.settlements where idempotency_key='race-settle-key'")
                .query(Long.class).single()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    /** Seeds available balance by ingesting confirmed payments (each paid as amount, fee 100). */
    private void fund(String... rawConfirmed) {
        for (String raw : rawConfirmed) {
            assertThat(ingestion.processMessage(raw)).isTrue();
        }
    }

    /** Builds a confirmed envelope JSON string with the given amount and a 100 bps fee. */
    private String payment(String txid, long amount) {
        return confirmedEnvelope(txid, amount, 100);
    }

    private String confirmedEnvelope(String txid, long amount, long fee) {
        long net = amount - fee;
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"payment.confirmed"
                + "\",\"version\":1,\"aggregateId\":\"" + txid
                + "\",\"merchantId\":\"" + MERCHANT + "\",\"requestId\":\"req-" + txid
                + "\",\"occurredAt\":\"" + FIXED_CLOCK.instant() + "\",\"payload\":{"
                + "\"amount\":" + amount + ",\"fee\":" + fee + ",\"net\":" + net
                + ",\"late\":false,\"txid\":\"" + txid + "\"}}";
    }

    private HttpResponse<String> postSettlement(String idemKey) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/ledger/settlements"))
                .header("Authorization", "Bearer " + rawKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idemKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private long available() {
        return balance("merchant:" + MERCHANT + ":available");
    }

    private long balance(String account) {
        return jdbc.sql("select balance_cents from ledger.balances where account = :a")
                .param("a", account).query(Long.class).optional().orElse(0L);
    }

    private long journalEntries() {
        return jdbc.sql("select count(*) from ledger.journal_entries").query(Long.class).single();
    }

    private long settlementCount() {
        return jdbc.sql("select count(*) from ledger.settlements").query(Long.class).single();
    }

    private long auditCount(String command) {
        return jdbc.sql("select count(*) from ledger.audit_log where command = :c")
                .param("c", command).query(Long.class).single();
    }

    private void assertProofOk() {
        var proof = reconciliation.proof();
        assertThat(proof.ok())
                .withFailMessage("proof failed: %s", proof.firstDivergence())
                .isTrue();
    }

    @TestConfiguration
    static class SettlementTestConfig {

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
    }
}
