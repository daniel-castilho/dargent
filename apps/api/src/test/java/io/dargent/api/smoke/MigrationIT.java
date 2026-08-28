package io.dargent.api.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * M0 acceptance proof: Flyway runs per-module locations against a real PostgreSQL 16 and creates
 * exactly the module-owned schemas — nothing else (design.md §5, D2).
 * M0 uses a plain @Container; the singleton-container pattern arrives when more IT classes share it
 * (lessons.md #6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class MigrationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void flyway_creates_all_module_schemas() {
        List<String> schemas = jdbc
                .sql("select schema_name from information_schema.schemata")
                .query(String.class)
                .list();

        assertThat(schemas).contains("payments", "ledger", "notifications");
    }

    @Test
    void module_schemas_start_empty_of_business_tables_in_m0() {
        // Schemas exist; business tables arrive with their milestone (expand/contract from day one).
        List<String> paymentTables = jdbc
                .sql("select table_name from information_schema.tables where table_schema = 'payments'")
                .query(String.class)
                .list();

        assertThat(paymentTables).isEmpty();
    }
}
