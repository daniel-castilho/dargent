package io.dargent.ledger.application;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.port.out.LedgerStore;
import io.dargent.ledger.domain.port.out.LedgerStore.ProofResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerReconciliationUseCaseTest {

    private LedgerReconciliationUseCase useCase;
    private FakeLedgerStore store;

    @BeforeEach
    void setUp() {
        store = new FakeLedgerStore(new ProofResult(true, null, 3, 10, 30));
        useCase = new LedgerReconciliationUseCase(store);
    }

    @Test
    void proof_returns_ok_with_counts() {
        ProofResult result = useCase.proof();
        assertThat(result.ok()).isTrue();
        assertThat(result.accountsChecked()).isEqualTo(3L);
        assertThat(result.entriesChecked()).isEqualTo(10L);
        assertThat(result.postingsChecked()).isEqualTo(30L);
    }

    @Test
    void proof_surfaces_divergence_when_not_ok() {
        store = new FakeLedgerStore(new ProofResult(false, "Per-account divergence: x", 3, 10, 30));
        useCase = new LedgerReconciliationUseCase(store);
        ProofResult result = useCase.proof();
        assertThat(result.ok()).isFalse();
        assertThat(result.firstDivergence()).isEqualTo("Per-account divergence: x");
    }

    @Test
    void rebuild_recomputes_balances_and_rechecks_proof() {
        store.setProofAfterRebuild(new ProofResult(true, null, 3, 10, 30));
        ProofResult result = useCase.rebuild(java.util.UUID.randomUUID());
        assertThat(store.rebuildCount).isEqualTo(1);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void balance_returns_account_for_known_account() {
        store.seed("merchant:1:available", 9900L);
        Account a = useCase.balance("merchant:1:available");
        assertThat(a.balanceCents()).isEqualTo(9900L);
    }

    @Test
    void balance_throws_for_unknown_account() {
        assertThatThrownBy(() -> useCase.balance("merchant:nope:available"))
                .isInstanceOf(LedgerAccountNotFoundException.class);
    }

    static class FakeLedgerStore implements LedgerStore {
        private ProofResult proof;
        private final java.util.concurrent.ConcurrentHashMap<String, Account> balances =
                new java.util.concurrent.ConcurrentHashMap<>();
        int rebuildCount = 0;
        private ProofResult proofAfterRebuild;

        FakeLedgerStore(ProofResult proof) {
            this.proof = proof;
        }

        void seed(String account, long balance) {
            balances.put(account, new Account(account, balance, Instant.parse("2026-08-31T10:00:00Z"), null));
        }

        void setProofAfterRebuild(ProofResult p) {
            this.proofAfterRebuild = p;
        }

        @Override
        public boolean insertEventIfAbsent(java.util.UUID eventId, String type, String txid,
                java.util.UUID merchantId, String payload, String status, String note) {
            return false;
        }

        @Override
        public void postJournal(io.dargent.ledger.domain.model.JournalEntry entry) {
        }

        @Override
        public void upsertBalance(Account account) {
            balances.put(account.account(), account);
        }

        @Override
        public Optional<Account> findAccount(String account) {
            return Optional.ofNullable((Account) balances.get(account));
        }

        @Override
        public long availableBalance(java.util.UUID merchantId) {
            return findAccount("merchant:" + merchantId + ":available").map(Account::balanceCents).orElse(0L);
        }

        @Override
        public Optional<Account> lockAvailableBalance(java.util.UUID merchantId) {
            return findAccount("merchant:" + merchantId + ":available");
        }

        @Override
        public Optional<io.dargent.ledger.domain.model.Settlement> findSettlementByKey(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<io.dargent.ledger.domain.model.Settlement> insertSettlement(
                io.dargent.ledger.domain.model.Settlement settlement) {
            return Optional.of(settlement);
        }

        @Override
        public void rebuildBalances() {
            rebuildCount++;
        }

        @Override
        public void recordAudit(LedgerStore.AuditEntry audit) {
        }

        @Override
        public ProofResult verifyProof() {
            return proof;
        }

        @Override
        public Optional<String> findEventStatus(java.util.UUID eventId) {
            return Optional.empty();
        }

        @Override
        public boolean postRefund(java.util.UUID eventId, String txid, java.util.UUID merchantId,
                long amountCents, long feeReversalCents, String description,
                java.time.Instant createdAt, java.time.Clock clock) {
            return false;
        }

        @Override
        public int claimEventForResume(java.util.UUID eventId) {
            return 0;
        }
    }
}
