package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.ledger.application.EventIngestionUseCase;
import io.dargent.ledger.application.LedgerReconciliationUseCase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * E8 S6 race ITs (spec §7.4, scenarios 12/23): the database — not optimism — arbitrates the race.
 * Fixed inputs everywhere; the only nondeterminism is scheduling, which is the point.
 * <ul>
 *   <li><b>Payments-lock race (sc.12):</b> two concurrent 60% refunds of the same 100.00 payment hit
 *       the {@code FOR UPDATE} payment lock + version guard; exactly one returns 201 and the other
 *       {@code 409 refund_exceeds_remaining}. {@code refunded_cents} is exactly 6000 — never 12000.</li>
 *   <li><b>Ledger drain race (sc.23):</b> two concurrent {@code refund.created} events drain the same
 *       merchant {@code :available} account beyond remaining; the conditional
 *       {@code UPDATE ... WHERE balance_cents >= :drain} lets exactly one post, the other becomes
 *       {@code IGNORED} with a {@code refund_skipped_balance} audit row. Ledger proof stays balanced.</li>
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, RefundRaceIT.RefundRaceTestConfig.class},
    properties = {
        "dargent.relay.enabled=false",
        "dargent.ledger.consumer.enabled=false",
        "dargent.psp.webhook-secret=dev-only-secret"
    })
@Testcontainers
class RefundRaceIT {

    private static final int THREADS = 2;
    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
    private static final JsonMapper MAPPER = new JsonMapper();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EventIngestionUseCase ingestion;

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
        jdbc.sql("truncate payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                + "payments.audit_log, payments.payments, payments.api_keys, payments.refunds, "
                + "ledger.events, ledger.postings, ledger.journal_entries, ledger.balances, "
                + "ledger.audit_log restart identity cascade").update();
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(rawKey))
                .param("hash", ApiKeyHasher.hash(rawKey))
                .update();
    }

    /**
     * Scenario 12: two concurrent 60% refunds. Both individually within remaining and available, so
     * only the payment {@code FOR UPDATE} lock + version guard can decide. Exactly one wins.
     */
    @Test
    void concurrent_60_percent_refunds_yield_one_201_and_one_409_never_double_apply() throws Exception {
        String txid = txid("RAC");
        seedConferencePayment(txid, 10000, 100);
        fundLedgerAvailable(10000, 100); // available 9900; both 60% net (5940) individually pass the pre-check

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        List<Callable<Integer>> workers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final int n = i;
            workers.add(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return refundStatus(txid, "refund-race-" + n, "req-race-" + n, "{\"amount\":6000}");
            });
        }

        List<Future<Integer>> futures = executor.invokeAll(workers);
        executor.shutdownNow();
        List<Integer> statuses = new ArrayList<>(THREADS);
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }

        // Exactly one 201 (the winner); the other loses on the payment lock → 409.
        assertThat(statuses).contains(201, 409);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(1);

        // The refund was applied exactly once: refunded_cents = 6000, never 12000.
        assertThat(refundedCents(txid)).isEqualTo(6000);
        assertThat(refundCount(txid)).isEqualTo(1);
        assertThat(paymentStatus(txid)).isEqualTo("PARTIALLY_REFUNDED");
    }

    /**
     * Scenario 23: two concurrent refund.created events drain the same merchant {@code :available}
     * account beyond remaining. The conditional drain (balance >= net) lets exactly one post; the
     * other becomes IGNORED with a {@code refund_skipped_balance} audit row. Ledger proof stays
     * balanced (projection == Σ lines after the storm).
     */
    @Test
    void concurrent_ledger_drains_beyond_available_one_posts_one_ignored_and_projection_intact() throws Exception {
        // available 99.00 (9900). Race two refund.created events, each net drain 59.00 (5900):
        // 5900 <= 9900 (each fits) but 2*5900 = 11800 > 9900 (both cannot fit) → exactly one posts.
        String txid = txid("DRS");
        fundLedgerAvailable(10000, 100); // available 9900

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        List<Callable<Boolean>> workers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            workers.add(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return ingestion.processMessage(refundEnvelope(txid, 6000, 100, 5900));
            });
        }

        List<Future<Boolean>> futures = executor.invokeAll(workers);
        executor.shutdownNow();
        List<Boolean> results = new ArrayList<>(THREADS);
        for (Future<Boolean> f : futures) {
            results.add(f.get());
        }

        // Both ack (the loser is IGNORED, still acked); exactly one refund.created POSTED, one IGNORED.
        assertThat(results).hasSize(THREADS).allMatch(Boolean.TRUE::equals);
        long postedRefunds = countRefundEvents("POSTED");
        long ignoredRefunds = countRefundEvents("IGNORED");
        assertThat(postedRefunds).isEqualTo(1);
        assertThat(ignoredRefunds).isEqualTo(1);

        // The skipped refund was audited.
        assertThat(skipAuditCount()).isEqualTo(1);

        // The conditional drain let exactly one win: available 9900 - 5900 = 4000.
        assertThat(balance("merchant:" + MERCHANT + ":available")).isEqualTo(4000);
        assertThat(balance("payments:processing")).isEqualTo(-4000);
        assertThat(balance("fees:revenue")).isZero();
        assertProofOk(); // projection == Σ lines after the storm
    }

    // ------------------------------------------------------------------ helpers

    private void fundLedgerAvailable(long amount, long fee) {
        assertThat(ingestion.processMessage(confirmedEnvelope(txid("FND"), amount, fee))).isTrue();
    }

    private String txid(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 22);
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

    private String refundEnvelope(String txid, long amount, long feeReversal, long net) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"refund.created"
                + "\",\"version\":1,\"aggregateId\":\"" + txid
                + "\",\"merchantId\":\"" + MERCHANT + "\",\"requestId\":\"req-refund-" + txid
                + "\",\"occurredAt\":\"" + FIXED_CLOCK.instant() + "\",\"payload\":{"
                + "\"amount\":" + amount + ",\"feeRefund\":" + feeReversal + ",\"netRefund\":" + net
                + ",\"txid\":\"" + txid + "\",\"refundId\":\"" + UUID.randomUUID() + "\"}}";
    }

    private void seedConferencePayment(String txid, long amount, long fee) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-09-02T10:00:00Z");
        Instant confirmedAt = Instant.parse("2026-09-02T10:05:00Z");
        jdbc.sql("""
                insert into payments.payments (id, txid, merchant_id, description, amount_cents, status, version,
                    expires_at, end_to_end_id, fee_cents, net_cents, late_confirmation, refunded_cents, created_at,
                    confirmed_at, next_reconcile_at, reconcile_attempts)
                values (:id, :txid, :merchant, 'refund-it', :amount, 'CONFIRMED', 1,
                    :expiresAt, :e2e, :fee, :net, false, 0, :created, :confirmed, null, 0)
                """)
                .param("id", id)
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("amount", amount)
                .param("fee", fee)
                .param("net", amount - fee)
                .param("e2e", "E00416968202009221504E2345678910")
                .param("expiresAt", java.sql.Timestamp.from(createdAt.plusSeconds(3600)))
                .param("created", java.sql.Timestamp.from(createdAt))
                .param("confirmed", java.sql.Timestamp.from(confirmedAt))
                .update();
    }

    private int refundStatus(String txid, String idemKey, String requestId, String json) throws Exception {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/payments/" + txid + "/refunds"))
                .header("Authorization", "Bearer " + rawKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idemKey)
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
        return resp.statusCode();
    }

    private long balance(String account) {
        return jdbc.sql("select balance_cents from ledger.balances where account = :a")
                .param("a", account).query(Long.class).optional().orElse(0L);
    }

    private String paymentStatus(String txid) {
        return jdbc.sql("select status from payments.payments where txid = :t")
                .param("t", txid).query(String.class).single();
    }

    private long refundedCents(String txid) {
        return jdbc.sql("select refunded_cents from payments.payments where txid = :t")
                .param("t", txid).query(Long.class).single();
    }

    private long refundCount(String txid) {
        return jdbc.sql("select count(*) from payments.refunds where txid = :t")
                .param("t", txid).query(Long.class).single();
    }

    private long countRefundEvents(String status) {
        return jdbc.sql("select count(*) from ledger.events where type = 'refund.created' and status = :s")
                .param("s", status).query(Long.class).single();
    }

    private long skipAuditCount() {
        return jdbc.sql("select count(*) from ledger.audit_log where command = 'refund_skipped_balance'")
                .query(Long.class).single();
    }

    private void assertProofOk() {
        var proof = reconciliation.proof();
        assertThat(proof.ok())
                .withFailMessage("proof failed: %s", proof.firstDivergence())
                .isTrue();
    }

    @TestConfiguration
    static class RefundRaceTestConfig {

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
                    .cleanDisabled(false)
                    .load();
            flyway.clean();
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