package io.dargent.api.config;

import io.dargent.ledger.domain.port.out.LedgerStore;
import io.dargent.payments.domain.port.out.MerchantBalancePort;
import java.util.UUID;

/**
 * Composition-root adapter: bridges payments' {@link MerchantBalancePort} to the ledger's
 * {@link LedgerStore} (which already exposes {@code availableBalance(merchantId)}).
 * Lives in apps/api (composition root) so it may depend on both modules — no cross-module
 * import in payments or ledger modules (AGENTS §2.2).
 */
public final class LedgerMerchantBalanceAdapter implements MerchantBalancePort {

    private final LedgerStore ledgerStore;

    public LedgerMerchantBalanceAdapter(LedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    @Override
    public long available(UUID merchantId) {
        return ledgerStore.availableBalance(merchantId);
    }
}