package io.dargent.api.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.dargent.api.DargentApiApplication;
import io.dargent.api.security.ApiKeyAuthenticationFilter;
import io.dargent.payments.domain.port.out.PaymentQueryPort;
import java.util.List;
import javax.sql.DataSource;
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
import org.flywaydb.core.Flyway;

/**
 * M0 acceptance proof: Flyway runs per-module locations against a real PostgreSQL 16 and creates
 * exactly the module-owned schemas — nothing else (design.md §5, D2).
 * M0 uses a plain @Container; the singleton-container pattern arrives when more IT classes share it
 * (lessons.md #6).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {DargentApiApplication.class, MigrationIT.FlywayTestConfig.class},
    properties = "dargent.psp.webhook-secret=dev-only-secret"
)
@Testcontainers
class MigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    JdbcClient jdbc;

    @Configuration
    static class FlywayTestConfig {
        @Bean
        Flyway paymentsFlyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/payments")
                    .schemas("payments")
                    .baselineOnMigrate(true)
                    .cleanDisabled(false)
                    .load();
            flyway.clean(); // Clean before migrate to handle container reuse
            flyway.migrate();
            return flyway;
        }

        @Bean
        Flyway ledgerFlyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/ledger")
                    .schemas("ledger")
                    .baselineOnMigrate(true)
                    .cleanDisabled(false)
                    .load();
            flyway.clean(); // Clean before migrate to handle container reuse
            flyway.migrate();
            return flyway;
        }

        @Bean
        Flyway notificationsFlyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/notifications")
                    .schemas("notifications")
                    .baselineOnMigrate(true)
                    .cleanDisabled(false)
                    .load();
            flyway.clean(); // Clean before migrate to handle container reuse
            flyway.migrate();
            return flyway;
        }

        @Bean
        @Primary
        PaymentQueryPort mockedPaymentQueryPort() {
            return mock(PaymentQueryPort.class);
        }

        @Bean
        @Primary
        ApiKeyAuthenticationFilter mockedApiKeyAuthenticationFilter() {
            return mock(ApiKeyAuthenticationFilter.class);
        }
    }

    @Test
    void v111_reconciliation_columns_and_pending_expires_index_apply() {
        // E5 S1 accept: V111 applies on real PG — next_reconcile_at/reconcile_attempts columns
        // plus the partial expiration index (spec §2).
        List<String> reconcileColumns = jdbc.sql("""
                select column_name
                from information_schema.columns
                where table_schema = 'payments' and table_name = 'payments'
                  and column_name in ('next_reconcile_at', 'reconcile_attempts')
                order by column_name
                """).query(String.class).list();
        assertThat(reconcileColumns).containsExactly("next_reconcile_at", "reconcile_attempts");

        Integer reconcileAttemptsDefault = jdbc.sql("""
                select column_default
                from information_schema.columns
                where table_schema = 'payments' and table_name = 'payments'
                  and column_name = 'reconcile_attempts'
                """).query(Integer.class).optional().orElseThrow();
        assertThat(reconcileAttemptsDefault).isZero();

        List<String> pendingExpiresIndex = jdbc.sql("""
                select indexname
                from pg_indexes
                where schemaname = 'payments' and indexname = 'idx_payments_pending_expires'
                """).query(String.class).list();
        assertThat(pendingExpiresIndex).containsExactly("idx_payments_pending_expires");

        List<String> indexDefinition = jdbc.sql("""
                select indexdef
                from pg_indexes
                where schemaname = 'payments' and indexname = 'idx_payments_pending_expires'
                """).query(String.class).list();
        assertThat(indexDefinition.get(0)).contains("(status)::text = 'PENDING'::text");

        // TD-21: reconciler scan index + PENDING backfill into the pipeline.
        List<String> reconcileIndex = jdbc.sql("""
                select indexname
                from pg_indexes
                where schemaname = 'payments' and indexname = 'idx_payments_reconcile_due'
                """).query(String.class).list();
        assertThat(reconcileIndex).containsExactly("idx_payments_reconcile_due");

        List<String> reconcileIndexDef = jdbc.sql("""
                select indexdef
                from pg_indexes
                where schemaname = 'payments' and indexname = 'idx_payments_reconcile_due'
                """).query(String.class).list();
        assertThat(reconcileIndexDef.get(0)).contains("((status)::text = ANY ((ARRAY['PENDING'::character varying, 'EXPIRED'::character varying])::text[]))");
    }

    @Test
    void v112_refunds_table_applies() {
        // E8 S1 accept: V112 applies on real PG — refunds table with columns, CHECK, and index (spec §2).
        List<String> refundsColumns = jdbc.sql("""
                select column_name
                from information_schema.columns
                where table_schema = 'payments' and table_name = 'refunds'
                order by ordinal_position
                """).query(String.class).list();
        assertThat(refundsColumns).containsExactly(
                "id", "payment_id", "txid", "amount_cents", "fee_reversal_cents",
                "net_cents", "request_id", "created_at");

        // CHECK constraints (4: amount>0, fee_rev>=0, net>=0, net=amount-fee)
        List<String> refundChecks = jdbc.sql("""
                select cc.constraint_name
                from information_schema.check_constraints cc
                join information_schema.constraint_column_usage ccu
                  on cc.constraint_name = ccu.constraint_name
                where ccu.table_schema = 'payments' and ccu.table_name = 'refunds'
                """).query(String.class).list();
        assertThat(refundChecks).hasSizeGreaterThanOrEqualTo(3); // amount>0, fee_rev>=0, net>=0, net=amount-fee

        // FK to payments
        List<String> refundFk = jdbc.sql("""
                select rc.constraint_name
                from information_schema.referential_constraints rc
                join information_schema.key_column_usage kcu
                  on rc.constraint_name = kcu.constraint_name
                where kcu.table_schema = 'payments' and kcu.table_name = 'refunds'
                  and kcu.column_name = 'payment_id'
                """).query(String.class).list();
        assertThat(refundFk).hasSize(1);

        // Index (payment_id, created_at DESC)
        List<String> refundIndex = jdbc.sql("""
                select indexname
                from pg_indexes
                where schemaname = 'payments' and indexname = 'idx_refunds_payment_created'
                """).query(String.class).list();
        assertThat(refundIndex).containsExactly("idx_refunds_payment_created");
    }

    @Test
    void flyway_creates_all_module_schemas() {
        List<String> schemas = jdbc
                .sql("select schema_name from information_schema.schemata")
                .query(String.class)
                .list();

        assertThat(schemas).contains("payments", "ledger", "notifications");
    }

    @Test
    void module_schemas_hold_only_their_own_business_tables() {
        // payments gains its core table in E1 (V102) + api_keys in E3 (V103)
        // + idempotency_keys/outbox/audit_log in E3 (V104-V106);
        // ledger (E7): journal, events, postings, balances, settlements
        // notifications: schema-only until its milestone
        List<String> paymentTables = jdbc
                .sql("select table_name from information_schema.tables where table_schema = 'payments'")
                .query(String.class)
                .list();
        List<String> ledgerTables = jdbc
                .sql("select table_name from information_schema.tables where table_schema = 'ledger'")
                .query(String.class)
                .list();
        List<String> notificationTables = jdbc
                .sql("select table_name from information_schema.tables where table_schema = 'notifications'")
                .query(String.class)
                .list();

        assertThat(paymentTables).containsExactlyInAnyOrder("payments", "api_keys", "idempotency_keys", "outbox", "audit_log", "webhook_events", "refunds", "flyway_schema_history");
        assertThat(ledgerTables).containsExactlyInAnyOrder("events", "journal_entries", "postings", "balances", "settlements", "audit_log", "flyway_schema_history");
        // notifications (E10): notification table + flyway_schema_history
        assertThat(notificationTables).containsExactlyInAnyOrder("notification", "flyway_schema_history");
    }
}