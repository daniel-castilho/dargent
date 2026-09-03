package io.dargent.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import java.time.Instant;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Journal coverage auditor ITs (spec §6, DEBT-4). The auditor reads both schemas, so the test runs
 * against real PostgreSQL + Flyway (all three schema locations). All detail is inserted directly;
 * the auditor bean is invoked deterministically via {@link JournalCoverageAuditor#runOnce()} — no
 * scheduler ticks, no sleeps.
 *
 * <ul>
 *   <li><b>Phase A gap</b> — CONFIRMED payment without a POSTED {@code payment.confirmed} event:
 *       audited as {@code journal_coverage_gap} with {@code request_id} prefix {@code PHASE_A}, the
 *       txid in {@code aggregate_id}, {@code actor_key_id} NULL.</li>
 *   <li><b>Phase B gap</b> — POSTED {@code payment.confirmed} event without a CONFIRMED payment:
 *       audited with {@code PHASE_B} prefix.</li>
 *   <li><b>Clean scan</b> — matched CONFIRMED + POSTED pair yields zero gaps and zero audit rows.</li>
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, JournalCoverageAuditorIT.AuditorTestConfig.class},
    properties = {
        "DARGENT_JOURNAL_COVERAGE_ENABLED=true",
        "dargent.psp.webhook-secret=dev-only-secret"
    }
)
@Testcontainers
class JournalCoverageAuditorIT {

    private static final UUID MERCHANT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JournalCoverageAuditor auditor;

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate payments.audit_log, payments.payments, payments.api_keys, payments.outbox, "
                + "payments.idempotency_keys, ledger.events restart identity cascade").update();
    }

    // =============================================================== spec §6 gap directions

    @Test
    void phase_a_confirmed_payment_without_posted_journal_is_audited_as_gap() {
        String txid = txid("PA");
        insertConfirmedPayment(txid);

        assertThat(auditor.runOnce()).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(1);
        assertThat(jdbc.sql("select command_name from payments.audit_log")
                .query(String.class).single()).isEqualTo("journal_coverage_gap");
        assertThat(jdbc.sql("select aggregate_id from payments.audit_log")
                .query(String.class).single()).isEqualTo(txid);
        assertThat(jdbc.sql("select request_id from payments.audit_log")
                .query(String.class).single()).startsWith("PHASE_A:");
        assertThat(jdbc.sql("select actor_key_id from payments.audit_log where command_name='journal_coverage_gap'")
                .query((rs, i) -> rs.getObject("actor_key_id")).optional()).isEmpty();
    }

    @Test
    void phase_b_posted_journal_without_confirmed_payment_is_audited_as_gap() {
        String txid = txid("PB");
        insertPostedEvent(txid);

        assertThat(auditor.runOnce()).isEqualTo(1);
        assertThat(auditRows()).isEqualTo(1);
        assertThat(jdbc.sql("select request_id from payments.audit_log")
                .query(String.class).single()).startsWith("PHASE_B:");
        assertThat(jdbc.sql("select actor_key_id from payments.audit_log where command_name='journal_coverage_gap'")
                .query((rs, i) -> rs.getObject("actor_key_id")).optional()).isEmpty();
    }

    @Test
    void matched_confirmed_and_posted_pair_is_silent_clean_scan() {
        String txid = txid("MM");
        insertConfirmedPayment(txid);
        insertPostedEvent(txid);

        assertThat(auditor.runOnce()).isZero();
        assertThat(auditRows()).isZero();
    }

    // ========================================================================== helpers

    private void insertConfirmedPayment(String txid) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into payments.payments (id, txid, merchant_id, description, amount_cents, status, version,
                    expires_at, end_to_end_id, fee_cents, net_cents, late_confirmation, refunded_cents,
                    created_at, confirmed_at)
                values (:id, :txid, :merchant, 'auditor-it', 10000, 'CONFIRMED', 0,
                    :expiresAt, null, null, null, false, 0, :created, :confirmed)
                """)
                .param("id", id)
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("expiresAt", java.sql.Timestamp.from(Instant.parse("2026-09-02T10:00:00Z")))
                .param("created", java.sql.Timestamp.from(Instant.parse("2026-09-01T10:00:00Z")))
                .param("confirmed", java.sql.Timestamp.from(Instant.parse("2026-09-02T09:59:00Z")))
                .update();
    }

    private void insertPostedEvent(String txid) {
        jdbc.sql("""
                insert into ledger.events (event_id, type, txid, merchant_id, payload, status, note, received_at)
                values (:id, 'payment.confirmed', :txid, :merchant, :payload::jsonb, 'POSTED', null, :received)
                """)
                .param("id", UUID.randomUUID())
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("payload", "{\"type\":\"payment.confirmed\"}")
                .param("received", java.sql.Timestamp.from(Instant.parse("2026-09-02T10:00:00Z")))
                .update();
    }

    private long auditRows() {
        return jdbc.sql("select count(*) from payments.audit_log where command_name='journal_coverage_gap'")
                .query(Long.class).single();
    }

    private String txid(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 23);
    }

    @Configuration
    static class AuditorTestConfig {

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
    }
}