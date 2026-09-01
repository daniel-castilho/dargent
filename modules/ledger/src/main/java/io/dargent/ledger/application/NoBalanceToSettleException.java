package io.dargent.ledger.application;

import java.util.UUID;

/**
 * Raised when a settlement request finds no positive available balance (§5.5).
 * Maps to HTTP 409 {@code no_balance_to_settle} at the API boundary.
 */
public final class NoBalanceToSettleException extends RuntimeException {

    private final UUID merchantId;

    public NoBalanceToSettleException(UUID merchantId) {
        super("Merchant has no balance to settle: " + merchantId);
        this.merchantId = merchantId;
    }

    public UUID merchantId() {
        return merchantId;
    }
}
