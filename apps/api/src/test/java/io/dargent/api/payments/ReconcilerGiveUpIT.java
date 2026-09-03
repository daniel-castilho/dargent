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
 * Reconciler give-up IT (spec §7.4, scenario S5): a PENDING or EXPIRED payment whose resurrection
 * window ({@code expires_at + DARGENT_RECONCILER_GIVE_UP_HOURS}, default 72h) is past is <em>not</em>
 * confirmed even when the PSP reports PAID — the schedule is cleared (conditional
 * {@code next_reconcile_at = NULL}), {@code reconciliation_window_expired} is audited, and the row
 * stays PENDING/EXPIRED (no fake terminal state). Within the window the payment stays scheduled (TD-21:
 * give-up applies to PENDING and EXPIRED alike).
 * <p>
 * Uses {@link ReconciliationScheduler#runOnce()} for deterministic CI-proof testing; zero
 * {@code Thread.sleep}; injected {@link Clock}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, ReconcilerGiveUpIT.ReconcilerTestConfig.class},
    properties = {
        "dargent.psp.webhook-secret=dev-only-secret",
        "DARGENT_RECONCILER_ENABLED=true"
    }
)
@Testcontainers
class ReconcilerGiveUpIT {

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

    // ================================================================ spec §7.4 (give-up window)

    @Test
    void pending_payment_past_resurrection_window_is_not_confirmed_cleared_and_audited() {
        // expires_at long past the 72h give-up window (created_at precedes expires_at so the snapshot
        // is valid) → now() is well past expires_at + 72h.
        String txid = seedExpiredWithDueSchedule("PENDING", Instant.parse("2026-07-05T10:00:00Z"), 0);
        psp.state = PspStub.State.PAID; // even a PSP PAID must NOT confirm past the window

        assertThat(scheduler.runOnce()).isEqualTo(1);

        // Still PENDING — no resurrection, no fake terminal state.
        var row = jdbc.sql("select status, next_reconcile_at from payments.payments where txid=:t")
                .param("t", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getTimestamp(2)})
                .single();
        assertThat(row[0]).isEqualTo("PENDING");
        assertThat(row[1]).isNull(); // schedule cleared (conditional NULL)

        // No confirm side effects.
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from payments.audit_log where command_name='confirm_from_reconciliation' and aggregate_id=:t")
                .param("t", txid).query(Long.class).single()).isZero();

        // Give-up audited.
        assertThat(jdbc.sql("select count(*) from payments.audit_log where command_name='reconciliation_window_expired' and aggregate_id=:t")
                .param("t", txid).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void expired_payment_past_resurrection_window_is_cleared_and_not_resurrected() {
        // TD-21: give-up applies to EXPIRED rows too — a locally-expired payment past the window is
        // unscheduled (stopped being polled) and never resurrected.
        String txid = seedExpiredWithDueSchedule("EXPIRED", Instant.parse("2026-07-05T10:00:00Z"), 1);
        psp.state = PspStub.State.PAID;

        assertThat(scheduler.runOnce()).isEqualTo(1);

        var row = jdbc.sql("select status, next_reconcile_at from payments.payments where txid=:t")
                .param("t", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getTimestamp(2)})
                .single();
        assertThat(row[0]).isEqualTo("EXPIRED");
        assertThat(row[1]).isNull();

        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from payments.audit_log where command_name='reconciliation_window_expired' and aggregate_id=:t")
                .param("t", txid).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void payment_within_resurrection_window_stays_scheduled_and_advances_ladder() {
        // expires_at 30 minutes ago → window end (expires_at + 72h) is in the future: NOT past window.
        // PSP OPEN → reconcile pushes the ladder (reconcile_attempts 0 → 1), next_reconcile_at bumped.
        String txid = seedExpiredWithDueSchedule("PENDING", Instant.parse("2026-09-02T09:30:00Z"), 0);
        psp.state = PspStub.State.OPEN;

        assertThat(scheduler.runOnce()).isEqualTo(1);

        var row = jdbc.sql("select status, next_reconcile_at, reconcile_attempts from payments.payments where txid=:t")
                .param("t", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getTimestamp(2), rs.getInt(3)})
                .single();
        assertThat(row[0]).isEqualTo("PENDING");
        assertThat(row[1]).isNotNull(); // still scheduled (not given up)
        assertThat(row[2]).isEqualTo(1); // ladder advanced 0 → 1
        assertThat(jdbc.sql("select count(*) from payments.audit_log where command_name='reconciliation_window_expired' and aggregate_id=:t")
                .param("t", txid).query(Long.class).single()).isZero();
    }

    // ========================================================================== helpers

    private String seedExpiredWithDueSchedule(String status, Instant expiresAt, int reconcileAttempts) {
        UUID id = UUID.randomUUID();
        String txid = "GUW" + id.toString().replace("-", "").toUpperCase().substring(0, 22);
        // created_at must precede expires_at (snapshot invariant); both before the give-up window end.
        Instant createdAt = expiresAt.minus(Duration.ofDays(3));
        // next_reconcile_at in the past → due now.
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
                String e2e = state == State.PAID ? "\"E00416968202009221504E2345678910\"" : "null";
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