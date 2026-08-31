package io.dargent.ledger.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves ledger migrations (V202–V204) apply cleanly on PostgreSQL 16.
 * Uses flyway-database-postgresql extension for PostgreSQL 16+ support.
 */
@Testcontainers
class LedgerMigrationIT {

    @Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    static javax.sql.DataSource dataSource;

    @BeforeAll
    static void setUp() {
        postgres.start();
        var ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;
    }

    @AfterAll
    static void tearDown() {
        postgres.stop();
    }

    @Test
    void migrations_apply_and_create_expected_schema() throws SQLException {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/ledger")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();

        try (var conn = dataSource.getConnection()) {
            // 1) Tables exist
            assertTableExists(conn, "events");
            assertTableExists(conn, "journal_entries");
            assertTableExists(conn, "postings");
            assertTableExists(conn, "balances");
            assertTableExists(conn, "settlements");

            // 2) CHECK constraints present (spec §5.2)
            assertCheckExists(conn, "events", "status");
            assertCheckExists(conn, "postings", "direction");
            assertCheckExists(conn, "postings", "amount_cents");
            assertCheckExists(conn, "settlements", "amount_cents");

            // 3) PK / UNIQUE
            assertUniqueExists(conn, "events", "event_id");
            assertUniqueExists(conn, "journal_entries", "event_id");
            assertUniqueExists(conn, "settlements", "idempotency_key");

            // 4) FKs
            assertForeignKeyExists(conn, "journal_entries", "event_id", "events", "event_id");
            assertForeignKeyExists(conn, "postings", "entry_id", "journal_entries", "id");
            assertForeignKeyExists(conn, "settlements", "entry_id", "journal_entries", "id");
        }
    }

    private static void assertTableExists(java.sql.Connection conn, String table) throws java.sql.SQLException {
        try (var rs = conn.getMetaData().getTables(null, "ledger", table, null)) {
            org.assertj.core.api.Assertions.assertThat(rs.next()).as("table %s exists", table).isTrue();
        }
    }

    private static void assertCheckExists(java.sql.Connection conn, String table, String column) throws java.sql.SQLException {
        String sql = """
                SELECT 1 FROM information_schema.check_constraints cc
                JOIN information_schema.constraint_column_usage ccu
                  ON cc.constraint_name = ccu.constraint_name
                WHERE ccu.table_schema = 'ledger'
                  AND ccu.table_name = ?
                  AND ccu.column_name = ?
                """;
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (var rs = ps.executeQuery()) {
                boolean found = false;
                while (rs.next()) {
                    found = true;
                    break;
                }
                org.assertj.core.api.Assertions.assertThat(found).as("CHECK constraint on %s.%s", table, column).isTrue();
            }
        }
    }

    private static void assertUniqueExists(java.sql.Connection conn, String table, String column) throws java.sql.SQLException {
        String sql = """
                SELECT 1 FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_schema = 'ledger'
                  AND tc.table_name = ?
                  AND tc.constraint_type IN ('UNIQUE','PRIMARY KEY')
                  AND kcu.column_name = ?
                """;
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (var rs = ps.executeQuery()) {
                org.assertj.core.api.Assertions.assertThat(rs.next()).as("UNIQUE/PK on %s.%s", table, column).isTrue();
            }
        }
    }

    private static void assertForeignKeyExists(java.sql.Connection conn, String fromTable, String fromCol,
                                               String toTable, String toCol) throws java.sql.SQLException {
        String sql = """
                SELECT 1 FROM information_schema.referential_constraints rc
                JOIN information_schema.key_column_usage kcu
                  ON rc.constraint_name = kcu.constraint_name
                JOIN information_schema.key_column_usage ccu
                  ON rc.unique_constraint_name = ccu.constraint_name
                WHERE kcu.table_name = ?
                  AND kcu.column_name = ?
                  AND ccu.table_name = ?
                  AND ccu.column_name = ?
                """;
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, fromTable);
            ps.setString(2, fromCol);
            ps.setString(3, toTable);
            ps.setString(4, toCol);
            try (var rs = ps.executeQuery()) {
                org.assertj.core.api.Assertions.assertThat(rs.next()).as("FK %s.%s -> %s.%s", fromTable, fromCol, toTable, toCol).isTrue();
            }
        }
    }
}