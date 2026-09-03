package io.dargent.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyHasher;
import io.dargent.api.config.ExpirationScheduler;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Expiration scheduler ITs (spec §3, §7.1): due PENDING payments are expired,
 * outbox {@code payment.expired} + audit {@code expire_payment} (NULL actor) written,
 * not-due untouched, confirm-won race no-ops.
 * <p>
 * Uses {@link ExpirationScheduler#runOnce()} for deterministic CI-proof testing
 * (no {@code @Scheduled}, no {@code Thread.sleep}, injected {@link Clock}).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, ExpirationSchedulerIT.ExpirationTestConfig.class},
    properties = {
        "dargent.psp.webhook-secret=dev-only-secret",
        "DARGENT_EXPIRATION_ENABLED=true"
    }
)
@Testcontainers
class ExpirationSchedulerIT {

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
    ExpirationScheduler scheduler;

    @BeforeEach
    void setUp() {
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

    // =================================================================== spec §7.1: due → EXPIRED + outbox + audit

    @Test
    void due_payment_is_expired_with_outbox_row_and_null_actor_audit() {
        // Create a PENDING payment with deadline 2 minutes in the past (due)
        String txid = seedPending(Instant.parse("2026-09-02T09:58:00Z"));

        // Run one deterministic tick
        int expired = scheduler.runOnce();

        assertThat(expired).isEqualTo(1);

        // Payment status EXPIRED, version bumped
        var pmt = payment(txid);
        assertThat(pmt[0]).isEqualTo("EXPIRED");
        assertThat(pmt[1]).isEqualTo(1); // version

        // Outbox row: payment.expired v1 with {txid, expiresAt, amountCents}
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.expired'")
                .param("t", txid).query(Long.class).single()).isEqualTo(1);
        String payload = jdbc.sql("select payload::text from payments.outbox where aggregate_id=:t and type='payment.expired'")
                .param("t", txid).query(String.class).single();
        var pj = new tools.jackson.databind.json.JsonMapper().readTree(payload);
        assertThat(pj.at("/payload/txid").asText()).isEqualTo(txid);
        assertThat(pj.at("/payload/amountCents").asLong()).isEqualTo(10000);
        assertThat(pj.at("/payload/expiresAt").asText()).contains("2026-09-02T09:58:00");

        // Audit row: expire_payment with NULL actor (V110), correct merchant + txid
        UUID auditActor = jdbc.sql(
                "select actor_key_id from payments.audit_log where command_name='expire_payment' and aggregate_id=:t")
                .param("t", txid).query((rs, i) -> rs.getObject("actor_key_id", UUID.class)).stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        assertThat(auditActor).isNull();
        assertThat(jdbc.sql("select merchant_id from payments.audit_log where command_name='expire_payment' and aggregate_id=:t")
                .param("t", txid).query(UUID.class).single()).isEqualTo(MERCHANT);
    }

    // =================================================================== not-due untouched

    @Test
    void not_due_payments_are_left_untouched() {
        // Create a PENDING payment with deadline 1 minute in the future (NOT due)
        seedPending(Instant.parse("2026-09-02T10:01:00Z"));

        int expired = scheduler.runOnce();

        assertThat(expired).isZero();
        assertThat(jdbc.sql("select count(*) from payments.outbox where type='payment.expired'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from payments.audit_log where command_name='expire_payment'").query(Long.class).single()).isZero();
    }

    // =================================================================== race: confirm won → no-op

    @Test
    void confirm_won_race_is_a_no_op_with_zero_outbox_and_audit_writes() {
        String txid = seedPending(Instant.parse("2026-09-02T09:58:00Z"));

        // Simulate webhook confirming it AFTER the scan would see it but BEFORE our tick runs
        // (we don't actually run the webhook; we directly update the row to CONFIRMED)
        jdbc.sql("""
                update payments.payments set
                    status = 'CONFIRMED',
                    version = 1,
                    end_to_end_id = 'E9RACE0000000000000000000000001',
                    fee_cents = 100,
                    net_cents = 9900,
                    late_confirmation = false,
                    confirmed_at = '2026-09-02T09:30:00Z'
                where txid = :txid
                """).param("txid", txid).update();

        int expired = scheduler.runOnce();

        assertThat(expired).isZero();
        var pmt = payment(txid);
        assertThat(pmt[0]).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("select count(*) from payments.outbox where aggregate_id=:t and type='payment.expired'")
                .param("t", txid).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from payments.audit_log where command_name='expire_payment' and aggregate_id=:t")
                .param("t", txid).query(Long.class).single()).isZero();
    }

    // =================================================================== helpers

    private String seedPending(Instant expiresAt) {
        UUID id = UUID.randomUUID();
        // Generate uppercase txid like SecureRandomTxidGenerator (matches Txid normalization)
        String txid = "EXP" + id.toString().replace("-", "").toUpperCase().substring(0, 22);
        Instant created = expiresAt.minusSeconds(3600);
        jdbc.sql("""
                insert into payments.payments (id, txid, merchant_id, description, amount_cents, status, version,
                    expires_at, end_to_end_id, fee_cents, net_cents, late_confirmation, refunded_cents, created_at, confirmed_at,
                    reconcile_attempts)
                values (:id, :txid, :merchant, 'exp-it', 10000, 'PENDING', 0,
                    :expiresAt, null, null, null, false, 0, :created, null,
                    0)
                """)
                .param("id", id)
                .param("txid", txid)
                .param("merchant", MERCHANT)
                .param("expiresAt", java.sql.Timestamp.from(expiresAt))
                .param("created", java.sql.Timestamp.from(created))
                .update();
        return txid;
    }

    private Object[] payment(String txid) {
        return jdbc.sql(
                "select status, version from payments.payments where txid = :txid")
                .param("txid", txid)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)})
                .single();
    }

    @Configuration
    static class ExpirationTestConfig {

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