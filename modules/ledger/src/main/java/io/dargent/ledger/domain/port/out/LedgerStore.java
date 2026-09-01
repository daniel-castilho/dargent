package io.dargent.ledger.domain.port.out;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.model.JournalEntry;
import io.dargent.ledger.domain.model.Settlement;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for ledger persistence (spec §4, §5).
 * One implementation (JdbcLedgerStore) — no second access path.
 */
public interface LedgerStore {

    /**
     * Inserts an event if event_id is new (idempotency).
     * Returns true if inserted, false if duplicate (event_id already exists).
     */
    boolean insertEventIfAbsent(UUID eventId, String type, String txid, UUID merchantId,
            String payload, String status, String note);

    /**
     * Writes journal entry + postings + balances in a single transaction.
     * Assumes event was already inserted and status is POSTED.
     */
    void postJournal(JournalEntry entry);

    /**
     * Upserts balance per account (credit-positive).
     * Called in same transaction as postJournal.
     */
    void upsertBalance(Account account);

    /**
     * Finds account by name.
     */
    Optional<Account> findAccount(String account);

    /**
     * Reads available balance for a merchant (credit-positive).
     */
    long availableBalance(UUID merchantId);

    /**
     * Reads a merchant's available balance row FOR UPDATE (settlement race arbitration §6).
     * Locks the ledger.balances row so a concurrent confirm/settle serializes.
     * Must be called inside an active transaction.
     */
    Optional<Account> lockAvailableBalance(UUID merchantId);

    /**
     * Finds an existing settlement by idempotency key (idempotent replay check).
     */
    Optional<Settlement> findSettlementByKey(String idempotencyKey);

    /**
     * Inserts settlement with idempotency key.
     * Returns existing settlement if key exists (idempotent replay).
     */
    Optional<Settlement> insertSettlement(Settlement settlement);

    /**
     * Rebuilds ledger.balances from ledger.postings in one transaction.
     * Projection is disposable; the journal is truth (§5.4).
     */
    void rebuildBalances();

    /**
     * Writes a ledger command audit row (spec §5.6) — the ledger's own trail, no dependency on payments.
     */
    void recordAudit(AuditEntry audit);

    /**
     * Verifies proof: global Σ DEBIT = Σ CREDIT and per-account balance = Σ credits - Σ debits.
     * Returns divergence details if proof fails.
     */
    ProofResult verifyProof();

    /**
     * Proof result: ok when balanced; counts for the API diagnostic (§5.4).
     */
    record ProofResult(boolean ok, String firstDivergence,
            long accountsChecked, long entriesChecked, long postingsChecked) {
        public static ProofResult ok(long accounts, long entries, long postings) {
            return new ProofResult(true, null, accounts, entries, postings);
        }
    }

    /**
     * Ledger command audit row for mutating API commands (settlement, rebuild).
     * {@code created_at} is set by the database default (now()), not the application.
     */
    record AuditEntry(UUID id, String command, UUID actorKeyId, UUID merchantId, String target) {}
}