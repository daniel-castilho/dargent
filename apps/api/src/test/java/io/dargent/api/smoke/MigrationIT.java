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
    classes = {DargentApiApplication.class, MigrationIT.FlywayTestConfig.class}
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
        PaymentQueryPort paymentQueryPort() {
            return mock(PaymentQueryPort.class);
        }

        @Bean
        @Primary
        ApiKeyAuthenticationFilter apiKeyAuthenticationFilter() {
            return mock(ApiKeyAuthenticationFilter.class);
        }
    }

    @Autowired
    PaymentQueryPort paymentQueryPort;

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
        // ledger/notifications stay schema-only until their milestone (expand/contract from day one)).
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

        assertThat(paymentTables).containsExactlyInAnyOrder("payments", "api_keys", "idempotency_keys", "outbox", "audit_log", "webhook_events");
        assertThat(ledgerTables).isEmpty();
        assertThat(notificationTables).isEmpty();
    }
}