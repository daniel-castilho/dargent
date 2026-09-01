package io.dargent.ledger.application;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.model.Settlement;
import io.dargent.ledger.domain.port.out.LedgerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementUseCaseTest {

    private static final UUID ACTOR = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private SettlementUseCase useCase;
    private FakeLedgerStore store;
    private UUID merchant;

    @BeforeEach
    void setUp() {
        store = new FakeLedgerStore();
        merchant = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var txTemplate = mock(TransactionTemplate.class);
        when(txTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        useCase = new SettlementUseCase(store, txTemplate,
                Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), java.time.ZoneOffset.UTC));
    }

    @Test
    void settle_moves_full_available_balance_to_payouts() {
        store.seed("merchant:" + merchant + ":available", 9900L);
        store.seed("fees:revenue", 100L);
        store.seed("payments:processing", -10000L);

        SettlementUseCase.SettlementResult result = useCase.settle(merchant, "settle-key-1", ACTOR);

        assertThat(result.replay()).isFalse();
        assertThat(result.settlement().amountCents()).isEqualTo(9900L);
        assertThat(result.settlement().idempotencyKey()).isEqualTo("settle-key-1");
        // available -9900, payouts +9900
        assertThat(store.balanceOf("merchant:" + merchant + ":available")).isEqualTo(0L);
        assertThat(store.balanceOf("payouts:external")).isEqualTo(9900L);
    }

    @Test
    void settle_with_zero_balance_is_rejected() {
        assertThatThrownBy(() -> useCase.settle(merchant, "settle-key-2", ACTOR))
                .isInstanceOf(NoBalanceToSettleException.class);
        assertThat(store.settlements).isEmpty();
    }

    @Test
    void settle_with_no_account_is_zero_balance_rejected() {
        assertThatThrownBy(() -> useCase.settle(merchant, "settle-key-3", ACTOR))
                .isInstanceOf(NoBalanceToSettleException.class);
        assertThat(store.settlements).isEmpty();
    }

    @Test
    void settle_is_idempotent_by_key_replays_existing() {
        store.seed("merchant:" + merchant + ":available", 9900L);
        var first = useCase.settle(merchant, "settle-key-4", ACTOR);
        assertThat(first.replay()).isFalse();

        var replay = useCase.settle(merchant, "settle-key-4", ACTOR);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.settlement().id()).isEqualTo(first.settlement().id());
        assertThat(store.settlements).hasSize(1);
    }

    @Test
    void settle_after_existing_settle_with_more_balance_replays_original_amount() {
        store.seed("merchant:" + merchant + ":available", 9900L);
        var first = useCase.settle(merchant, "settle-key-5", ACTOR);
        useCase.settle(merchant, "settle-key-5", ACTOR);
        assertThat(store.settlements).hasSize(1);
        assertThat(first.settlement().amountCents()).isEqualTo(9900L);
    }

    @Test
    void settle_balance_drains_to_zero_after_confirm() {
        store.seed("merchant:" + merchant + ":available", 9900L);
        useCase.settle(merchant, "settle-key-6", ACTOR);
        assertThat(store.balanceOf("merchant:" + merchant + ":available")).isZero();

        // simulate a new confirm
        store.credit("merchant:" + merchant + ":available", 5000L);
        var second = useCase.settle(merchant, "settle-key-7", ACTOR);
        assertThat(second.replay()).isFalse();
        assertThat(second.settlement().amountCents()).isEqualTo(5000L);
        assertThat(store.balanceOf("merchant:" + merchant + ":available")).isZero();
    }

    static class FakeLedgerStore implements LedgerStore {
        final ConcurrentHashMap<String, Account> balances = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Settlement> settlements = new ConcurrentHashMap<>();

        void seed(String account, long balance) {
            balances.put(account, new Account(account, balance, Instant.parse("2026-08-31T10:00:00Z"), null));
        }

        void credit(String account, long amount) {
            balances.compute(account, (k, v) -> new Account(account,
                    (v == null ? 0L : v.balanceCents()) + amount, Instant.now(), null));
        }

        long balanceOf(String account) {
            return balances.get(account).balanceCents();
        }

        @Override
        public boolean insertEventIfAbsent(UUID eventId, String type, String txid, UUID merchantId,
                String payload, String status, String note) {
            return true;
        }

        @Override
        public void postJournal(io.dargent.ledger.domain.model.JournalEntry entry) {
            for (var p : entry.postings()) {
                long delta = p.direction() == io.dargent.ledger.domain.model.EntryDirection.CREDIT
                        ? p.amountCents() : -p.amountCents();
                credit(p.account(), delta);
            }
        }

        @Override
        public void upsertBalance(Account account) {
            balances.put(account.account(), account);
        }

        @Override
        public Optional<Account> findAccount(String account) {
            return Optional.ofNullable(balances.get(account));
        }

        @Override
        public long availableBalance(UUID merchantId) {
            return findAccount("merchant:" + merchantId + ":available").map(Account::balanceCents).orElse(0L);
        }

        @Override
        public Optional<Account> lockAvailableBalance(UUID merchantId) {
            Account a = balances.get("merchant:" + merchantId + ":available");
            return Optional.ofNullable(a);
        }

        @Override
        public Optional<Settlement> findSettlementByKey(String idempotencyKey) {
            return Optional.ofNullable(settlements.get(idempotencyKey));
        }

        @Override
        public Optional<Settlement> insertSettlement(Settlement settlement) {
            Settlement prev = settlements.putIfAbsent(settlement.idempotencyKey(), settlement);
            return prev == null ? Optional.of(settlement) : Optional.of(prev);
        }

        @Override
        public void rebuildBalances() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordAudit(LedgerStore.AuditEntry audit) {
        }

        @Override
        public ProofResult verifyProof() {
            return new ProofResult(true, null, 0, 0, 0);
        }

        @Override
        public Optional<String> findEventStatus(UUID eventId) {
            return Optional.empty();
        }

        @Override
        public int claimEventForResume(UUID eventId) {
            return 0;
        }
    }
}
