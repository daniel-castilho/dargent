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
     * Inserts settlement with idempotency key.
     * Returns existing settlement if key exists (idempotent replay).
     */
    Optional<Settlement> insertSettlement(Settlement settlement);

    /**
     * Verifies proof: global Σ DEBIT = Σ CREDIT and per-account balance = Σ credits - Σ debits.
     * Returns divergence details if proof fails.
     */
    ProofResult verifyProof();

    record ProofResult(boolean ok, String firstDivergence) {}
}