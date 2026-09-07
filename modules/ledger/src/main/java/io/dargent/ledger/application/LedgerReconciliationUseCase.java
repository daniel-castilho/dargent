package io.dargent.ledger.application;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.port.out.LedgerStore;
import java.util.UUID;

/**
 * Ledger read + reconciliation use cases (spec §5.4, §5.6).
 * Proof is a diagnostic (200 with ok:false on divergence, not an error). Rebuild recomputes the
 * disposable balances projection from the append-only journal, then re-verifies proof.
 */
public final class LedgerReconciliationUseCase {

    private final LedgerStore store;
    private final LedgerMetrics metrics;

    public LedgerReconciliationUseCase(LedgerStore store) {
        this(store, null);
    }

    public LedgerReconciliationUseCase(LedgerStore store, LedgerMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    public LedgerStore.ProofResult proof() {
        LedgerStore.ProofResult result = store.verifyProof();
        if (!result.ok() && metrics != null) {
            metrics.proofFail(result.scope());
        }
        return result;
    }

    /**
     * E13 R3: admin-proof path. Audited as {@code ledger_admin_proof} with the presented key's
     * real identity (never a sentinel) — admin access is accountable even though the proof is
     * read-only. The no-actor overload stays for internal schedulers (JournalCoverageAuditor).
     */
    public LedgerStore.ProofResult proof(UUID actorKeyId) {
        store.recordAudit(
                new LedgerStore.AuditEntry(UUID.randomUUID(), "ledger_admin_proof", actorKeyId, null, "proof"));
        return proof();
    }

    public LedgerStore.ProofResult rebuild(UUID actorKeyId) {
        store.recordAudit(
                new LedgerStore.AuditEntry(UUID.randomUUID(), "ledger_admin_rebuild", actorKeyId, null, "balances"));
        store.rebuildBalances();
        return store.verifyProof();
    }

    /**
     * Returns the account or throws {@link LedgerAccountNotFoundException} if unknown (§5.6 → 404).
     */
    public Account balance(String account) {
        return store.findAccount(account).orElseThrow(() -> new LedgerAccountNotFoundException(account));
    }

    public long availableBalanceFor(UUID merchantId) {
        return store.availableBalance(merchantId);
    }
}
