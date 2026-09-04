package io.dargent.ledger.domain.port.out;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.model.JournalEntry;
import io.dargent.ledger.domain.model.Settlement;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for ledger persistence (spec §4, §5).
 * One implementation (JdbcLedgerStore) — no second access path.
 */
public interface LedgerStore {

    /**
     * Finds the current status of an event by its ID.
     */
    Optional<String> findEventStatus(UUID eventId);

    /**
     * Attempts to claim a RECEIVED event for resume posting.
     * Conditionally updates status from RECEIVED to POSTED.
     * Returns number of affected rows (1 = claimed, 0 = already claimed by another consumer).
     */
    int claimEventForResume(UUID eventId);

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
     * Posts a refund journal entry with conditional balance drain (spec §5 refund.created).
     * Performs conditional drain on `merchant:{id}:available` for net drain = amount - feeReversal.
     * If drain fails (0 rows), the journal is NOT posted and event is marked IGNORED with
     * note `insufficient_merchant_balance` + audit `refund_skipped_balance`.
     * Returns true if posted, false if drain failed (IGNORED).
     */
    boolean postRefund(UUID eventId, String txid, UUID merchantId, long amountCents, long feeReversalCents,
            String description, Instant createdAt, Clock clock);

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
     * Checks if a POSTED journal entry already exists for the given txid (payment.confirmed).
     * Used to prevent double-journaling of republished events (scenario 20 / E9 §6.4).
     */
    boolean hasPostedJournalForTxid(String txid);

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