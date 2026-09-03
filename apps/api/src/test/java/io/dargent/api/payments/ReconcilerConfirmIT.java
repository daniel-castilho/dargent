package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.dargent.api.DargentApiApplication;
import io.dargent.api.config.ReconciliationScheduler;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.payments.adapter.out.psp.SimulatorChargeAdapter;
import io.dargent.payments.domain.model.Txid;
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
 * Reconciler confirm ITs (spec §7.2, scenario 26): a PENDING payment whose PSP cob is PAID is
 * confirmed by the reconciler with {@code late=false}, outbox {@code payment.confirmed} and audit
 * {@code confirm_from_reconciliation} (NULL actor); a second {@link ReconciliationScheduler#runOnce()}
 * is an idempotent no-op (already CONFIRMED → zero writes). The webhook is suppressed — the
 * reconciler is the only confirmer.
 * <p>
 * Uses {@link ReconciliationScheduler#runOnce()} for deterministic CI-proof testing; zero
 * {@code Thread.sleep}; injected {@link Clock}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, ReconcilerConfirmIT.ReconcilerTestConfig.class},
    properties = {
        "dargent.psp.webhook-secret=dev-only-secret",
        "DARGENT_RECONCILER_ENABLED=true"
    }
)
@Testcontainers
class ReconcilerConfirmIT {

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

    // ================================================================ spec §7.2 scenario 26

    @Test
    void psp_paid_payment_is_confirmed_with_late_false_outbox_and_audit() {
        String txid = seedPendingWithDueSchedule();

        psp.state = PspStub.State.PAID;

        int reconciled = scheduler.runOnce();

        assertThat(reconciled).isEqualTo(1);

        var pmt = jdbc.sql("select status, late_confirmation, version, end_to_end_id from payments.payments where txid=:t")
                .param("t", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getBoolean(2), rs.getInt(3), rs.getString(4)})
                .single();
        assertThat(pmt[0]).isEqualTo("CONFIRMED");
        assertThat(pmt[1]).isEqualTo(Boolean.FALSE); // late=false (was PENDING, not expired)
        assertThat(pmt[2]).isEqualTo(1);
        assertThat(pmt[3]).isEqualTo(PSP_E2E);

        // Outbox: payment.confirmed with {amount, fee, net, late:false}
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single()).isEqualTo(1);
        String payload = jdbc.sql("select payload::text from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(String.class).single();
        var pj = new tools.jackson.databind.json.JsonMapper().readTree(payload);
        assertThat(pj.at("/payload/amount").asLong()).isEqualTo(10000);
        assertThat(pj.at("/payload/fee").asLong()).isEqualTo(100);
        assertThat(pj.at("/payload/net").asLong()).isEqualTo(9900);
        assertThat(pj.at("/payload/late").asBoolean()).isFalse();

        // Audit: confirm_from_reconciliation with NULL actor (V110), correct merchant + txid
        UUID auditActor = jdbc.sql(
                "select actor_key_id from payments.audit_log where command_name='confirm_from_reconciliation' and aggregate_id=:t")
                .param("t", txid).query((rs, i) -> rs.getObject("actor_key_id", UUID.class)).stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        assertThat(auditActor).isNull();
        assertThat(jdbc.sql("select merchant_id from payments.audit_log where command_name='confirm_from_reconciliation' and aggregate_id=:t")
                .param("t", txid).query(UUID.class).single()).isEqualTo(MERCHANT);
    }

    @Test
    void second_reconciliation_run_on_confirmed_payment_is_an_idempotent_no_op() {
        String txid = seedPendingWithDueSchedule();
        psp.state = PspStub.State.PAID;

        assertThat(scheduler.runOnce()).isEqualTo(1);

        // Already CONFIRMED → second run must not write a second outbox/audit row (exactly-once).
        assertThat(scheduler.runOnce()).isZero();

        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.confirmed'")
                .param("t", txid).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from payments.audit_log where command_name='confirm_from_reconciliation' and aggregate_id=:t")
                .param("t", txid).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select status from payments.payments where txid=:t").param("t", txid)
                .query(String.class).single()).isEqualTo("CONFIRMED");
    }

    // ========================================================================== helpers

    private static final String PSP_E2E = "E00416968202009221504E2345678910";

    private String seedPendingWithDueSchedule() {
        UUID id = UUID.randomUUID();
        String txid = "REC" + id.toString().replace("-", "").toUpperCase().substring(0, 22);
        Instant createdAt = Instant.parse("2026-09-02T09:00:00Z");
        Instant expiresAt = Instant.parse("2026-09-03T10:00:00Z");
        // next_reconcile_at in the past → due now (owner decision: initial schedule set at create)
        Instant nextReconcileAt = FIXED_CLOCK.instant().minusSeconds(1);
        jdbc.sql("""
                insert into payments.payments (id, txid, merchant_id, description, amount_cents, status, version,
                    expires_at, end_to_end_id, fee_cents, net_cents, late_confirmation, refunded_cents, created_at,
                    confirmed_at, next_reconcile_at, reconcile_attempts)
                values (:id, :txid, :merchant, 'reconciler-it', 10000, 'PENDING', 0,
                    :expiresAt, null, null, null, false, 0, :created, null,
                    :nextReconcileAt, 0)
                """)
                .param("id", id)
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .param("created", java.sql.Timestamp.from(createdAt))
                .param("nextReconcileAt", java.sql.Timestamp.from(nextReconcileAt))
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