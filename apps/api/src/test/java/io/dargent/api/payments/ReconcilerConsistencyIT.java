package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.dargent.api.DargentApiApplication;
import io.dargent.api.config.ReconciliationScheduler;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.payments.adapter.out.psp.SimulatorChargeAdapter;
import io.dargent.payments.domain.port.out.PspPort;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Reconciler consistency legs (spec §7.6; playbook scenarios 9 + 10) — the E5-side of the
 * exactly-once guarantee the webhook intake ITs prove on the inbound seam. Both legs seed a payment
 * that the reconciler confirms (PENDING late=false, EXPIRED resurrect late=true), then "replay" the
 * reconciliation: any force/re-arm of a duplicate scheduler delivery after a payment is already
 * CONFIRMED must leave the final state identical — exactly one {@code payment.confirmed} outbox and
 * one {@code confirm_from_reconciliation} audit, never a duplicate.
 * <p>
 * <b>Scenario 9 leg</b> — out-of-order/late confirmation → final state consistent: a second
 * {@code runOnce()} over an already-CONFIRMED payment is a zero-write no-op (the conditional
 * {@code UPDATE ... WHERE status IN (PENDING,EXPIRED)} gate swallows the loser).
 * <p>
 * <b>Scenario 10 leg</b> — replaying a reconciliation yields the same result: repeated runs keep the
 * outbox and audit counts pinned at one each (idempotent; the analogue of {@code payload_raw} replay
 * producing the same single result, here on the confirm path the reconciler owns).
 * <p>
 * Uses {@link ReconciliationScheduler#runOnce()} for deterministic CI-proof testing; zero
 * {@code Thread.sleep}; injected {@link Clock}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, ReconcilerConsistencyIT.ReconcilerTestConfig.class},
    properties = {
        "dargent.psp.webhook-secret=dev-only-secret",
        "DARGENT_RECONCILER_ENABLED=true"
    }
)
@Testcontainers
class ReconcilerConsistencyIT {

    private static final UUID MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ReconciliationScheduler scheduler;

    @Autowired
    PspStub psp;

    @BeforeEach
    void setUp() {
        psp.reset();
        jdbc.sql("truncate payments.webhook_events, payments.outbox, payments.idempotency_keys, "
                + "payments.audit_log, payments.payments, payments.api_keys restart identity cascade").update();
        jdbc.sql(
                "insert into payments.api_keys (id, merchant_id, name, key_prefix, key_hash, created_at, revoked_at) "
                        + "values (:id, :merchant, 'it-key', :prefix, :hash, now(), null)")
                .param("id", KEY_ID)
                .param("merchant", MERCHANT)
                .param("prefix", ApiKeyHasher.prefix(ApiKeyHasher.generateRawKey()))
                .param("hash", ApiKeyHasher.hash(ApiKeyHasher.generateRawKey()))
                .update();
    }

    // =============================================================== spec §7.6 scenarios 9 + 10

    @Test
    void scenario9_out_of_order_duplicate_delivery_after_confirm_keeps_final_state_consistent() {
        String txid = seed("PENDING", Instant.parse("2026-09-03T10:00:00Z"), 0);
        psp.state = PspStub.State.PAID;

        assertThat(scheduler.runOnce()).isEqualTo(1); // first (in-order) confirm

        assertCounts(txid, 1, 1); // 1 outbox + 1 audit
        assertThat(state(txid)).isEqualTo("CONFIRMED");

        // Out-of-order / duplicate scheduler delivery arrives AFTER the payment is confirmed: the
        // conditional gate does not re-confirm, so the final state is unchanged and consistent.
        assertThat(scheduler.runOnce()).isZero();
        assertThat(state(txid)).isEqualTo("CONFIRMED");
        assertCounts(txid, 1, 1);
    }

    @Test
    void scenario10_replaying_the_reconciliation_yields_the_identical_single_result() {
        // Resurrected path (EXPIRED -> CONFIRMED late=true): replaying the reconciler must yield the
        // same single result, never a duplicate outbox or audit.
        String txid = seed("EXPIRED", Instant.parse("2026-09-02T09:30:00Z"), 1);
        psp.state = PspStub.State.PAID;

        assertThat(scheduler.runOnce()).isEqualTo(1);
        assertThat(state(txid)).isEqualTo("CONFIRMED");
        boolean late = jdbc.sql("select late_confirmation from payments.payments where txid=:t")
                .param("t", txid).query(Boolean.class).single();
        assertThat(late).isTrue();

        // Replay: two more full cycles (and a give-up-window re-arm attempt on a non-CONFIRMED twin
        // would no-op too) keep exactly one outbox + one audit.
        assertThat(scheduler.runOnce()).isZero();
        assertThat(scheduler.runOnce()).isZero();
        assertCounts(txid, 1, 1);
        assertThat(state(txid)).isEqualTo("CONFIRMED");
    }

    // ========================================================================== helpers

    private static final String PSP_E2E = "E00416968202009221504E2345678910";

    private String state(String txid) {
        return jdbc.sql("select status from payments.payments where txid=:t")
                .param("t", txid).query(String.class).single();
    }

    private void assertCounts(String txid, long outboxExpected, long auditExpected) {
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single()).isEqualTo(outboxExpected);
        assertThat(jdbc.sql("select count(*) from payments.audit_log where command_name='confirm_from_reconciliation' and aggregate_id=:t")
                .param("t", txid).query(Long.class).single()).isEqualTo(auditExpected);
    }

    private String seed(String status, Instant expiresAt, int reconcileAttempts) {
        UUID id = UUID.randomUUID();
        String txid = "SC9" + id.toString().replace("-", "").toUpperCase().substring(0, 22);
        Instant createdAt = expiresAt.minus(Duration.ofDays(1));
        Instant nextReconcileAt = FIXED_CLOCK.instant().minusSeconds(1);
        int version = "EXPIRED".equals(status) ? 1 : 0;
        jdbc.sql("""
                insert into payments.payments (id, txid, merchant_id, description, amount_cents, status, version,
                    expires_at, end_to_end_id, fee_cents, net_cents, late_confirmation, refunded_cents, created_at,
                    confirmed_at, next_reconcile_at, reconcile_attempts)
                values (:id, :txid, :merchant, 'reconciler-it', 10000, :status, :version,
                    :expiresAt, null, null, null, false, 0, :created, null,
                    :nextReconcileAt, :attempts)
                """)
                .param("id", id)
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("status", status)
                .param("version", version)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .param("created", java.sql.Timestamp.from(createdAt))
                .param("nextReconcileAt", java.sql.Timestamp.from(nextReconcileAt))
                .param("attempts", reconcileAttempts)
                .update();
        return txid;
    }

    @Configuration
    static class ReconcilerTestConfig {

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

        @Bean
        PspStub pspStub() {
            return new PspStub();
        }

        @Bean
        HttpServer pspServer(PspStub psp) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/cobs", psp::handle);
            server.start();
            return server;
        }

        @Bean
        @Primary
        PspPort pspTestPort(HttpServer server, PspStub psp) {
            int port = server.getAddress().getPort();
            return new SimulatorChargeAdapter("http://127.0.0.1:" + port, 3, Duration.ofMillis(20), psp::sleeper);
        }
    }

    /** Stateful HttpHandler for the PSP stub: creates charges and serves GET /cobs/{txid} with a state. */
    static final class PspStub {
        enum State { OPEN, PAID, EXPIRED }

        volatile State state = State.PAID;

        long sleeper() {
            return 0L;
        }

        void reset() {
            state = State.PAID;
        }

        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] respBody;
            int status;
            if ("POST".equals(method) && "/cobs".equals(path)) {
                status = 200;
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String txid = extractTxid(body);
                respBody = ("{\"txid\":\"" + txid + "\",\"status\":\"OPEN\",\"amountCents\":10000,"
                        + "\"expiresAt\":\"2026-09-03T10:00:00Z\",\"endToEndId\":null,\"paidAt\":null}")
                        .getBytes(StandardCharsets.UTF_8);
            } else if ("GET".equals(method) && path.startsWith("/cobs/")) {
                String txid = path.substring("/cobs/".length());
                status = 200;
                String e2e = state == State.PAID ? "\"" + PSP_E2E + "\"" : "null";
                String paidAt = state == State.PAID ? "\"2026-09-02T09:59:30Z\"" : "null";
                respBody = ("{\"txid\":\"" + txid + "\",\"state\":\"" + state + "\",\"amountCents\":10000,"
                        + "\"expiresAt\":\"2026-09-03T10:00:00Z\",\"endToEndId\":" + e2e + ",\"paidAt\":" + paidAt + "}")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                respBody = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, respBody.length);
            exchange.getResponseBody().write(respBody);
            exchange.close();
        }

        private String extractTxid(String body) {
            int i = body.indexOf("\"txid\"");
            int start = body.indexOf("\"", i + 7) + 1;
            int end = body.indexOf("\"", start);
            return body.substring(start, end);
        }
    }
}