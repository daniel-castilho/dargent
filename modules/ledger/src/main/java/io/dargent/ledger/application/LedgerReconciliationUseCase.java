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

    public LedgerReconciliationUseCase(LedgerStore store) {
        this.store = store;
    }

    public LedgerStore.ProofResult proof() {
        return store.verifyProof();
    }

    public LedgerStore.ProofResult rebuild(UUID actorKeyId) {
        store.recordAudit(new LedgerStore.AuditEntry(UUID.randomUUID(), "REBUILD", actorKeyId, null, "balances"));
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
