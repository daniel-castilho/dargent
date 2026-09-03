package io.dargent.ledger.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEBT-5 barrier: DB-level deferred constraint/trigger enforcing journal balance at commit.
 * Test-local DDL (not a migration); if a real migration is needed for production, STOP-and-report.
 * Validates that an unbalanced journal entry fails at commit time.
 */
@Testcontainers
class JournalBalanceDbBarrierIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource dataSource;

    @BeforeAll
    static void setUp() {
        postgres.start();
        var ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;

        // Apply ledger migrations
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/ledger")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();

// Add test-local deferred trigger for journal balance (E5-style inline DDL)
            try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
                // Create function to check journal balance
                stmt.execute("""
                    CREATE OR REPLACE FUNCTION ledger.check_journal_balance()
                    RETURNS TRIGGER LANGUAGE plpgsql AS $$
                    DECLARE
                        v_entry_id uuid;
                        v_sum_debit bigint;
                        v_sum_credit bigint;
                    BEGIN
                        -- Only check on commit (deferred)
                        IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
                            -- Check all journals that have postings in this transaction
                            FOR v_entry_id IN
                                SELECT DISTINCT entry_id FROM ledger.postings
                                WHERE entry_id IN (
                                    SELECT entry_id FROM ledger.postings
                                    WHERE ctid IN (
                                        SELECT ctid FROM ledger.postings
                                        WHERE xmax = 0 -- only rows visible in this transaction
                                    )
                                )
                            LOOP
                                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_cents ELSE 0 END), 0)
                                INTO v_sum_debit
                                FROM ledger.postings WHERE entry_id = v_entry_id;

                                SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_cents ELSE 0 END), 0)
                                INTO v_sum_credit
                                FROM ledger.postings WHERE entry_id = v_entry_id;

                                IF v_sum_debit != v_sum_credit THEN
                                    RAISE EXCEPTION 'Journal entry % does not balance: debit=% credit=%',
                                        v_entry_id, v_sum_debit, v_sum_credit
                                    USING ERRCODE = 'integrity_constraint_violation';
                                END IF;
                            END LOOP;
                        END IF;
                        RETURN NULL; -- AFTER trigger
                    END;
                    $$;
                """);

                // Deferred constraint trigger on postings - fires at commit
                stmt.execute("""
                    DROP TRIGGER IF EXISTS trg_check_journal_balance ON ledger.postings;
                    CREATE CONSTRAINT TRIGGER trg_check_journal_balance
                    AFTER INSERT OR UPDATE ON ledger.postings
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION ledger.check_journal_balance();
                """);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to install test-local DB barrier", e);
            }
    }

    @AfterAll
    static void tearDown() {
        postgres.stop();
    }

    @Test
    void balanced_journal_commits_successfully() throws SQLException {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            var entryId = UUID.randomUUID();
            var eventId = UUID.randomUUID();
            var merchantId = UUID.randomUUID();

            // First insert the event (FK requirement)
            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.events (event_id, type, txid, merchant_id, payload, status, note)
                    VALUES (?, 'payment.confirmed', ?, ?, '{}'::jsonb, 'POSTED', 'test')""")) {
                ps.setObject(1, eventId);
                ps.setString(2, "TX-BALANCED");
                ps.setObject(3, merchantId);
                ps.executeUpdate();
            }

            // Journal entry
            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.journal_entries (id, event_id, txid, merchant_id, description, created_at)
                    VALUES (?, ?, ?, ?, ?, now())""")) {
                ps.setObject(1, entryId);
                ps.setObject(2, eventId);
                ps.setString(3, "TX-BALANCED");
                ps.setObject(4, merchantId);
                ps.setString(5, "balanced test");
                ps.executeUpdate();
            }

            // Two balanced postings: DEBIT 1000 + CREDIT 1000
            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.postings (id, entry_id, account, direction, amount_cents, created_at)
                    VALUES (?, ?, ?, ?::text, ?, now())""")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, entryId);
                ps.setString(3, "account:debit");
                ps.setString(4, "DEBIT");
                ps.setLong(5, 1000);
                ps.executeUpdate();

                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, entryId);
                ps.setString(3, "account:credit");
                ps.setString(4, "CREDIT");
                ps.setLong(5, 1000);
                ps.executeUpdate();
            }

            conn.commit(); // Should succeed - deferred trigger fires here
        }
    }

    @Test
    void unbalanced_journal_fails_at_commit() throws SQLException {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            var entryId = UUID.randomUUID();
            var eventId = UUID.randomUUID();
            var merchantId = UUID.randomUUID();

            // First insert the event
            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.events (event_id, type, txid, merchant_id, payload, status, note)
                    VALUES (?, 'payment.confirmed', ?, ?, '{}'::jsonb, 'POSTED', 'test')""")) {
                ps.setObject(1, eventId);
                ps.setString(2, "TX-UNBALANCED");
                ps.setObject(3, merchantId);
                ps.executeUpdate();
            }

            // Journal entry
            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.journal_entries (id, event_id, txid, merchant_id, description, created_at)
                    VALUES (?, ?, ?, ?, ?, now())""")) {
                ps.setObject(1, entryId);
                ps.setObject(2, eventId);
                ps.setString(3, "TX-UNBALANCED");
                ps.setObject(4, merchantId);
                ps.setString(5, "unbalanced test");
                ps.executeUpdate();
            }

            // Unbalanced postings: DEBIT 1000 + CREDIT 900 (sum !=)
            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.postings (id, entry_id, account, direction, amount_cents, created_at)
                    VALUES (?, ?, ?, ?::text, ?, now())""")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, entryId);
                ps.setString(3, "account:debit");
                ps.setString(4, "DEBIT");
                ps.setLong(5, 1000);
                ps.executeUpdate();

                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, entryId);
                ps.setString(3, "account:credit");
                ps.setString(4, "CREDIT");
                ps.setLong(5, 900);
                ps.executeUpdate();
            }

            assertThatThrownBy(() -> conn.commit())
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Journal entry");
        }
    }

    @Test
    void single_posting_fails() throws SQLException {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            var entryId = UUID.randomUUID();
            var eventId = UUID.randomUUID();
            var merchantId = UUID.randomUUID();

            // First insert the event
            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.events (event_id, type, txid, merchant_id, payload, status, note)
                    VALUES (?, 'payment.confirmed', ?, ?, '{}'::jsonb, 'POSTED', 'test')""")) {
                ps.setObject(1, eventId);
                ps.setString(2, "TX-SINGLE");
                ps.setObject(3, merchantId);
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.journal_entries (id, event_id, txid, merchant_id, description, created_at)
                    VALUES (?, ?, ?, ?, ?, now())""")) {
                ps.setObject(1, entryId);
                ps.setObject(2, eventId);
                ps.setString(3, "TX-SINGLE");
                ps.setObject(4, merchantId);
                ps.setString(5, "single posting test");
                ps.executeUpdate();
            }

            // Only one posting (violates ≥2 rule at domain level, but DB should also catch if it sneaks past)
            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.postings (id, entry_id, account, direction, amount_cents, created_at)
                    VALUES (?, ?, ?, ?::text, ?, now())""")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, entryId);
                ps.setString(3, "account:debit");
                ps.setString(4, "DEBIT");
                ps.setLong(5, 1000);
                ps.executeUpdate();
            }

            // The domain ctor prevents this, but if it somehow sneaks past, the trigger catches
            // (journal with 1 posting has debit=1000, credit=0 → imbalance)
            assertThatThrownBy(() -> conn.commit())
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Journal entry");
        }
    }

    @Test
    void zero_amount_posting_fails_check_constraint() throws SQLException {
        // The existing CHECK constraint on amount_cents > 0 catches this at INSERT time (not deferred)
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            var entryId = UUID.randomUUID();
            var eventId = UUID.randomUUID();
            var merchantId = UUID.randomUUID();

            // First insert the event
            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.events (event_id, type, txid, merchant_id, payload, status, note)
                    VALUES (?, 'payment.confirmed', ?, ?, '{}'::jsonb, 'POSTED', 'test')""")) {
                ps.setObject(1, eventId);
                ps.setString(2, "TX-ZERO");
                ps.setObject(3, merchantId);
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement("""
                    INSERT INTO ledger.journal_entries (id, event_id, txid, merchant_id, description, created_at)
                    VALUES (?, ?, ?, ?, ?, now())""")) {
                ps.setObject(1, entryId);
                ps.setObject(2, eventId);
                ps.setString(3, "TX-ZERO");
                ps.setObject(4, merchantId);
                ps.setString(5, "zero amount test");
                ps.executeUpdate();
            }

            // CHECK constraint fires at INSERT time, not deferred
            assertThatThrownBy(() -> {
                try (var ps = conn.prepareStatement("""
                        INSERT INTO ledger.postings (id, entry_id, account, direction, amount_cents, created_at)
                        VALUES (?, ?, ?, ?::text, ?, now())""")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, entryId);
                    ps.setString(3, "account:debit");
                    ps.setString(4, "DEBIT");
                    ps.setLong(5, 0); // zero amount - violates existing CHECK
                    ps.executeUpdate();
                }
            }).isInstanceOf(SQLException.class)
                    .hasMessageContaining("postings_amount_cents_check");
        }
    }
}