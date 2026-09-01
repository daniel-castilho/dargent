package io.dargent.ledger.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves ledger migrations (V202–V207) apply cleanly on PostgreSQL 16.
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
            assertTableExists(conn, "audit_log");

            // 2) CHECK constraints present (spec §5.2)
            assertCheckExists(conn, "events", "status");
            assertCheckExists(conn, "postings", "direction");
            assertCheckExists(conn, "postings", "amount_cents");
            assertCheckExists(conn, "settlements", "amount_cents");

            // event_id nullable (V205) so settlement entries with no envelope event can be written
            assertColumnNullable(conn, "journal_entries", "event_id", true);

            // 3) PK / UNIQUE
            assertUniqueExists(conn, "events", "event_id");
            assertUniqueExists(conn, "journal_entries", "event_id");
            assertUniqueExists(conn, "settlements", "idempotency_key");

            // 4) FKs
            assertForeignKeyExists(conn, "journal_entries", "event_id", "events", "event_id");
            assertForeignKeyExists(conn, "postings", "entry_id", "journal_entries", "id");
            assertForeignKeyExists(conn, "settlements", "entry_id", "journal_entries", "id");

            // 5) V207: the events.status CHECK admits the ingestion RECEIVED state (spec §5.3)
            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.events (event_id, type, txid, merchant_id, payload, status, note)
                    VALUES (?, 'payment.confirmed', 'mig-it-v207', '11111111-1111-1111-1111-111111111111',
                            '{}'::jsonb, 'RECEIVED', 'V207 probe')""")) {
                ps.setObject(1, java.util.UUID.randomUUID());
                assertThat(ps.executeUpdate()).as("V207 admits RECEIVED status").isEqualTo(1);
            }
        }
    }

    private static void assertTableExists(java.sql.Connection conn, String table) throws java.sql.SQLException {
        try (var rs = conn.getMetaData().getTables(null, "ledger", table, null)) {
            org.assertj.core.api.Assertions.assertThat(rs.next()).as("table %s exists", table).isTrue();
        }
    }

    private static void assertColumnNullable(java.sql.Connection conn, String table, String column,
            boolean expectedNullable) throws java.sql.SQLException {
        String sql = """
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'ledger' AND table_name = ? AND column_name = ?
                """;
        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (var rs = ps.executeQuery()) {
                org.assertj.core.api.Assertions.assertThat(rs.next()).as("column %s.%s exists", table, column).isTrue();
                String nullable = rs.getString(1);
                org.assertj.core.api.Assertions.assertThat("YES".equals(nullable))
                        .as("column %s.%s nullable=%s", table, column, expectedNullable)
                        .isEqualTo(expectedNullable);
            }
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