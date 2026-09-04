package io.dargent.ledger.adapter.out.db;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.model.EntryDirection;
import io.dargent.ledger.domain.model.JournalEntry;
import io.dargent.ledger.domain.model.Posting;
import io.dargent.ledger.domain.model.Settlement;
import io.dargent.ledger.domain.port.out.LedgerStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC implementation of LedgerStore (spec §4, §5).
 * Uses Spring JdbcClient — zero JPA, zero Hibernate.
 */
public final class JdbcLedgerStore implements LedgerStore {

    /**
     * System actor used when the ledger initiates an audit row with no API key:
     * the insufficient-balance refund skip (BD-14 sentinel pattern; see
     * {@code WebhookIntakeUseCase.WEBHOOK_AUDIT_ACTOR}). {@code ledger.audit_log.actor_key}
     * is NOT NULL, so a sentinel is required.
     */
    private static final UUID SYSTEM_AUDIT_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

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
    public Optional<String> findEventStatus(UUID eventId) {
        return jdbc.sql("SELECT status FROM ledger.events WHERE event_id = ?")
                .param(eventId)
                .query(String.class)
                .optional();
    }

    @Override
    public int claimEventForResume(UUID eventId) {
        return jdbc.sql("""
                UPDATE ledger.events
                SET status = 'POSTED', note = 'Posted successfully'
                WHERE event_id = ? AND status = 'RECEIVED'
                """)
                .param(eventId)
                .update();
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
                            entry.description(), Timestamp.from(entry.createdAt()))
                    .update();

            // 2) Postings
            for (Posting p : entry.postings()) {
                jdbc.sql("""
                        INSERT INTO ledger.postings (id, entry_id, account, direction, amount_cents, created_at)
                        VALUES (?, ?, ?, ?::text, ?, ?)
                        """)
                        .params(p.id(), p.entryId(), p.account(), p.direction().name(), p.amountCents(),
                                Timestamp.from(p.createdAt()))
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
                        .params(p.account(), delta, Timestamp.from(entry.createdAt()), entry.eventId())
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
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
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
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
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
                        rs.getObject("settled_at", OffsetDateTime.class).toInstant()
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
                        settlement.amountCents(), settlement.entryId(), Timestamp.from(settlement.settledAt()))
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
                        rs.getObject("settled_at", OffsetDateTime.class).toInstant()
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

    @Override
    public boolean postRefund(UUID eventId, String txid, UUID merchantId, long amountCents, long feeReversalCents,
            String description, Instant createdAt, Clock clock) {
        return txTemplate.execute(txStatus -> {
            // Conditional drain on available balance: net = amount - feeReversal
            long netDrain = amountCents - feeReversalCents;
            int drained = jdbc.sql("""
                    UPDATE ledger.balances
                    SET balance_cents = balance_cents - ?, updated_at = ?, last_event_id = ?
                    WHERE account = ? AND balance_cents >= ?
                    """)
                    .params(netDrain, Timestamp.from(clock.instant()), eventId,
                            "merchant:" + merchantId + ":available", netDrain)
                    .update();

            if (drained == 0) {
                // Drain failed — insufficient balance. Mark event IGNORED with note.
                jdbc.sql("""
                        UPDATE ledger.events
                        SET status = 'IGNORED', note = 'insufficient_merchant_balance'
                        WHERE event_id = ?
                        """)
                        .param(eventId)
                        .update();
                // Audit the skipped refund (system actor, real merchant; actor_key is NOT NULL)
                recordAudit(new AuditEntry(UUID.randomUUID(), "refund_skipped_balance",
                        SYSTEM_AUDIT_ACTOR, merchantId, "txid=" + txid + ",event=" + eventId));
                return false;
            }

            // Update processing and fees:revenue balances directly (available already drained)
            // [3] Cr payments:processing amount (CREDIT increases processing balance toward zero)
            jdbc.sql("""
                    INSERT INTO ledger.balances (account, balance_cents, updated_at, last_event_id)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (account) DO UPDATE SET
                        balance_cents = ledger.balances.balance_cents + EXCLUDED.balance_cents,
                        updated_at = EXCLUDED.updated_at,
                        last_event_id = EXCLUDED.last_event_id
                    """)
                    .params("payments:processing", amountCents, Timestamp.from(createdAt), eventId)
                    .update();
            // [4] Dr fees:revenue feeReversal (DEBIT decreases fees revenue)
            jdbc.sql("""
                    INSERT INTO ledger.balances (account, balance_cents, updated_at, last_event_id)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (account) DO UPDATE SET
                        balance_cents = ledger.balances.balance_cents + EXCLUDED.balance_cents,
                        updated_at = EXCLUDED.updated_at,
                        last_event_id = EXCLUDED.last_event_id
                    """)
                    .params("fees:revenue", -feeReversalCents, Timestamp.from(createdAt), eventId)
                    .update();

            // Build postings: [3] Dr available / Cr processing, [4] Dr fees:revenue / Cr available
            UUID entryId = UUID.randomUUID();
            var postings = List.of(
                    new Posting(UUID.randomUUID(), entryId, "merchant:" + merchantId + ":available",
                            EntryDirection.DEBIT, amountCents, clock.instant()),
                    new Posting(UUID.randomUUID(), entryId, "payments:processing",
                            EntryDirection.CREDIT, amountCents, clock.instant()),
                    new Posting(UUID.randomUUID(), entryId, "fees:revenue",
                            EntryDirection.DEBIT, feeReversalCents, clock.instant()),
                    new Posting(UUID.randomUUID(), entryId, "merchant:" + merchantId + ":available",
                            EntryDirection.CREDIT, feeReversalCents, clock.instant())
            );

            var entry = new JournalEntry(
                    entryId,
                    eventId,
                    txid,
                    merchantId,
                    description,
                    createdAt,
                    postings
            );

            // Write journal + postings WITHOUT balance updates (already done above)
            postJournalWithoutBalances(entry);
            return true;
        });
    }

    /**
     * Posts journal entry and postings without updating balances.
     * Used by postRefund where balances are updated atomically with the conditional drain.
     */
    private void postJournalWithoutBalances(JournalEntry entry) {
        txTemplate.execute(status -> {
            // 1) Journal entry
            jdbc.sql("""
                    INSERT INTO ledger.journal_entries (id, event_id, txid, merchant_id, description, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)
                    .params(entry.id(), entry.eventId(), entry.txid(), entry.merchantId(),
                            entry.description(), Timestamp.from(entry.createdAt()))
                    .update();

            // 2) Postings only (no balance upserts)
            for (Posting p : entry.postings()) {
                jdbc.sql("""
                        INSERT INTO ledger.postings (id, entry_id, account, direction, amount_cents, created_at)
                        VALUES (?, ?, ?, ?::text, ?, ?)
                        """)
                        .params(p.id(), p.entryId(), p.account(), p.direction().name(), p.amountCents(),
                                Timestamp.from(p.createdAt()))
                        .update();
            }
            return null;
        });
    }
}