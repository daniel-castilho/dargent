package io.dargent.payments.domain.port.out;

import java.util.UUID;

/**
 * Port for querying a merchant's available balance from the ledger.
 * The adapter lives in the composition root ({@code apps/api}) and delegates to
 * the ledger's existing balance query. The payments module only declares the contract.
 */
public interface MerchantBalancePort {

    /**
     * Returns the available balance (in cents) for the given merchant.
     *
     * @param merchantId the merchant identifier
     * @return available balance in cents, or 0 if no account exists
     */
    long available(UUID merchantId);
}