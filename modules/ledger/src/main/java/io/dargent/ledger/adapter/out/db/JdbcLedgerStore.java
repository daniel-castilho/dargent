package io.dargent.ledger.adapter.out.db;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.model.JournalEntry;
import io.dargent.ledger.domain.model.Posting;
import io.dargent.ledger.domain.model.Settlement;
import io.dargent.ledger.domain.port.out.LedgerStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of LedgerStore (spec §4, §5).
 * Uses Spring JdbcClient — zero JPA, zero Hibernate.
 */
public final class JdbcLedgerStore implements LedgerStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate txTemplate;

    public JdbcLedgerStore(JdbcClient jdbc, TransactionTemplate txTemplate) {
        this.jdbc = jdbc;
        this.txTemplate = txTemplate;
    }

    @Override
    public boolean insertEventIfAbsent(UUID eventId, String type, String txid, UUID merchantId,
            String payload, String status, String note) {
        int rows = jdbc.sql("""
                INSERT INTO ledger.events (event_id, type, txid, merchant_id, payload, status, note)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .params(eventId, type, txid, merchantId, payload, status, note)
                .update();
        return rows > 0;
    }

    @Override
    public void postJournal(JournalEntry entry) {
        txTemplate.execute(status -> {
            // 1) Journal entry
            jdbc.sql("""
                    INSERT INTO ledger.journal_entries (id, event_id, txid, merchant_id, description, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)
                    .params(entry.id(), entry.eventId(), entry.txid(), entry.merchantId(),
                            entry.description(), entry.createdAt())
                    .update();

            // 2) Postings
            for (Posting p : entry.postings()) {
                jdbc.sql("""
                        INSERT INTO ledger.postings (id, entry_id, account, direction, amount_cents, created_at)
                        VALUES (?, ?, ?, ?::text, ?, ?)
                        """)
                        .params(p.id(), p.entryId(), p.account(), p.direction().name(), p.amountCents(), p.createdAt())
                        .update();
            }

            // 3) Balance upserts
            for (Posting p : entry.postings()) {
                long delta = p.direction() == io.dargent.ledger.domain.model.EntryDirection.CREDIT
                        ? p.amountCents() : -p.amountCents();
                jdbc.sql("""
                        INSERT INTO ledger.balances (account, balance_cents, updated_at, last_event_id)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (account) DO UPDATE SET
                            balance_cents = ledger.balances.balance_cents + EXCLUDED.balance_cents,
                            updated_at = EXCLUDED.updated_at,
                            last_event_id = EXCLUDED.last_event_id
                        """)
                        .params(p.account(), delta, entry.createdAt(), entry.eventId())
                        .update();
            }
            return null;
        });
    }

    @Override
    public void upsertBalance(Account account) {
        jdbc.sql("""
                INSERT INTO ledger.balances (account, balance_cents, updated_at, last_event_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (account) DO UPDATE SET
                    balance_cents = EXCLUDED.balance_cents,
                    updated_at = EXCLUDED.updated_at,
                    last_event_id = EXCLUDED.last_event_id
                """)
                .params(account.account(), account.balanceCents(), account.updatedAt(), account.lastEventId())
                .update();
    }

    @Override
    public Optional<Account> findAccount(String account) {
        return jdbc.sql("""
                SELECT account, balance_cents, updated_at, last_event_id
                FROM ledger.balances
                WHERE account = ?
                """)
                .param(account)
                .query((rs, rowNum) -> new Account(
                        rs.getString("account"),
                        rs.getLong("balance_cents"),
                        rs.getObject("updated_at", Instant.class),
                        (UUID) rs.getObject("last_event_id")
                ))
                .optional();
    }

    @Override
    public long availableBalance(UUID merchantId) {
        String account = "merchant:" + merchantId + ":available";
        return findAccount(account).map(Account::balanceCents).orElse(0L);
    }

    @Override
    public Optional<Account> lockAvailableBalance(UUID merchantId) {
        String account = "merchant:" + merchantId + ":available";
        return jdbc.sql("""
                SELECT account, balance_cents, updated_at, last_event_id
                FROM ledger.balances
                WHERE account = ?
                FOR UPDATE
                """)
                .param(account)
                .query((rs, rowNum) -> new Account(
                        rs.getString("account"),
                        rs.getLong("balance_cents"),
                        rs.getObject("updated_at", Instant.class),
                        (UUID) rs.getObject("last_event_id")
                ))
                .optional();
    }

    @Override
    public Optional<Settlement> findSettlementByKey(String idempotencyKey) {
        return jdbc.sql("""
                SELECT id, merchant_id, idempotency_key, amount_cents, entry_id, settled_at
                FROM ledger.settlements
                WHERE idempotency_key = ?
                """)
                .param(idempotencyKey)
                .query((rs, rowNum) -> new Settlement(
                        rs.getObject("id", UUID.class),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getString("idempotency_key"),
                        rs.getLong("amount_cents"),
                        rs.getObject("entry_id", UUID.class),
                        rs.getObject("settled_at", Instant.class)
                ))
                .optional();
    }

    @Override
    public void rebuildBalances() {
        txTemplate.execute(status -> {
            jdbc.sql("DELETE FROM ledger.balances").update();
            jdbc.sql("""
                    INSERT INTO ledger.balances (account, balance_cents, updated_at, last_event_id)
                    SELECT p.account,
                           SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount_cents
                                    ELSE -p.amount_cents END) AS balance_cents,
                           MAX(p.created_at) AS updated_at,
                           NULL::uuid AS last_event_id
                    FROM ledger.postings p
                    GROUP BY p.account
                    """)
                    .update();
            return null;
        });
    }

    @Override
    public Optional<Settlement> insertSettlement(Settlement settlement) {
        int rows = jdbc.sql("""
                INSERT INTO ledger.settlements (id, merchant_id, idempotency_key, amount_cents, entry_id, settled_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .params(settlement.id(), settlement.merchantId(), settlement.idempotencyKey(),
                        settlement.amountCents(), settlement.entryId(), settlement.settledAt())
                .update();
        if (rows > 0) {
            return Optional.of(settlement);
        }
        // Fetch existing
        return jdbc.sql("""
                SELECT id, merchant_id, idempotency_key, amount_cents, entry_id, settled_at
                FROM ledger.settlements
                WHERE idempotency_key = ?
                """)
                .param(settlement.idempotencyKey())
                .query((rs, rowNum) -> new Settlement(
                        rs.getObject("id", UUID.class),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getString("idempotency_key"),
                        rs.getLong("amount_cents"),
                        rs.getObject("entry_id", UUID.class),
                        rs.getObject("settled_at", Instant.class)
                ))
                .optional();
    }

    @Override
    public void recordAudit(AuditEntry audit) {
        jdbc.sql("""
                INSERT INTO ledger.audit_log (id, command, actor_key, merchant_id, target)
                VALUES (?, ?, ?, ?, ?)
                """)
                .params(audit.id(), audit.command(), audit.actorKeyId(), audit.merchantId(), audit.target())
                .update();
    }

    @Override
    public ProofResult verifyProof() {
        long accountsChecked = jdbc.sql("SELECT COUNT(*) FROM ledger.balances")
                .query(Long.class).single();
        long entriesChecked = jdbc.sql("SELECT COUNT(*) FROM ledger.journal_entries")
                .query(Long.class).single();
        long postingsChecked = jdbc.sql("SELECT COUNT(*) FROM ledger.postings")
                .query(Long.class).single();

        // (a) global Σ DEBIT = Σ CREDIT
        long totalDebit = jdbc.sql("""
                SELECT COALESCE(SUM(amount_cents), 0)
                FROM ledger.postings
                WHERE direction = 'DEBIT'
                """)
                .query(Long.class).single();

        long totalCredit = jdbc.sql("""
                SELECT COALESCE(SUM(amount_cents), 0)
                FROM ledger.postings
                WHERE direction = 'CREDIT'
                """)
                .query(Long.class).single();

        if (totalDebit != totalCredit) {
            return new ProofResult(false, "Global imbalance: debit=" + totalDebit + " != credit=" + totalCredit,
                    accountsChecked, entriesChecked, postingsChecked);
        }

        // (b) per account: balance_cents == Σ credits - Σ debits
        var divergences = jdbc.sql("""
                SELECT b.account, b.balance_cents,
                       COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount_cents ELSE 0 END), 0) AS credits,
                       COALESCE(SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount_cents ELSE 0 END), 0) AS debits
                FROM ledger.balances b
                LEFT JOIN ledger.postings p ON p.account = b.account
                GROUP BY b.account, b.balance_cents
                HAVING b.balance_cents != (COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount_cents ELSE 0 END), 0)
                                          - COALESCE(SUM(CASE WHEN p.direction = 'DEBIT' THEN p.amount_cents ELSE 0 END), 0))
                """)
                .query((rs, rowNum) -> rs.getString("account") + ": balance=" + rs.getLong("balance_cents")
                        + " != credits-debits=" + (rs.getLong("credits") - rs.getLong("debits")))
                .list();

        if (!divergences.isEmpty()) {
            return new ProofResult(false, "Per-account divergence: " + divergences.get(0),
                    accountsChecked, entriesChecked, postingsChecked);
        }

        // (c) every journal entry has ≥ 2 postings
        long badEntries = jdbc.sql("""
                SELECT COUNT(*) FROM ledger.journal_entries je
                WHERE (SELECT COUNT(*) FROM ledger.postings p WHERE p.entry_id = je.id) < 2
                """)
                .query(Long.class).single();

        if (badEntries > 0) {
            return new ProofResult(false, badEntries + " journal entries with < 2 postings",
                    accountsChecked, entriesChecked, postingsChecked);
        }

        return ProofResult.ok(accountsChecked, entriesChecked, postingsChecked);
    }
}