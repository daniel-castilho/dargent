package io.dargent.ledger.application;

import io.dargent.ledger.domain.model.Account;
import io.dargent.ledger.domain.model.EntryDirection;
import io.dargent.ledger.domain.model.JournalEntry;
import io.dargent.ledger.domain.model.Posting;
import io.dargent.ledger.domain.model.Settlement;
import io.dargent.ledger.domain.port.out.LedgerStore;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Settlement use case (spec §5.5): moves a merchant's full available balance to payouts in one
 * transaction. Race-safe via {@code SELECT … FOR UPDATE} on the merchant's ledger.balances row (§6).
 * Idempotent by {@code Idempotency-Key} → idempotency_key UNIQUE; replay returns the existing settlement.
 */
public final class SettlementUseCase {

    private final LedgerStore store;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public SettlementUseCase(LedgerStore store, TransactionTemplate txTemplate, Clock clock) {
        this.store = store;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    /**
     * Settles a merchant's available balance. One transaction:
     * replay-check (idempotency key) → FOR UPDATE balance → journal + postings → settlement row + audit.
     *
     * @throws NoBalanceToSettleException when the available balance is ≤ 0
     */
    public SettlementResult settle(UUID merchantId, String idempotencyKey, UUID actorKeyId) {
        return txTemplate.execute(status -> {
            Optional<Settlement> existing = store.findSettlementByKey(idempotencyKey);
            if (existing.isPresent()) {
                return SettlementResult.replay(existing.get());
            }

            long balance = store.lockAvailableBalance(merchantId)
                    .map(Account::balanceCents)
                    .orElse(0L);
            if (balance <= 0) {
                throw new NoBalanceToSettleException(merchantId);
            }

            Instant now = clock.instant();
            UUID entryId = UUID.randomUUID();
            var postings = List.of(
                    new Posting(UUID.randomUUID(), entryId,
                            "merchant:" + merchantId + ":available",
                            EntryDirection.DEBIT, balance, now),
                    new Posting(UUID.randomUUID(), entryId,
                            "payouts:external",
                            EntryDirection.CREDIT, balance, now)
            );
            var entry = new JournalEntry(entryId, null, idempotencyKey, merchantId,
                    "Settlement for merchant " + merchantId, now, postings);
            store.postJournal(entry);

            var settlement = new Settlement(UUID.randomUUID(), merchantId, idempotencyKey,
                    balance, entryId, now);
            Settlement persisted = store.insertSettlement(settlement).orElse(settlement);
            store.recordAudit(new LedgerStore.AuditEntry(UUID.randomUUID(),
                    "SETTLE", actorKeyId, merchantId, idempotencyKey));
            return SettlementResult.created(persisted);
        });
    }

    /**
     * Settlement outcome for the API boundary.
     * {@code replay} distinguishes an idempotent replay (Idempotent-Replay: true) from a fresh settle.
     */
    public record SettlementResult(Settlement settlement, boolean replay) {
        static SettlementResult created(Settlement settlement) {
            return new SettlementResult(settlement, false);
        }

        static SettlementResult replay(Settlement settlement) {
            return new SettlementResult(settlement, true);
        }
    }
}
