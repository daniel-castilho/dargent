package io.dargent.ledger.application;

/**
 * Raised when a ledger account lookup targets an unknown account (§5.6).
 * Maps to HTTP 404 {@code account_not_found} at the API boundary.
 */
public final class LedgerAccountNotFoundException extends RuntimeException {

    private final String account;

    public LedgerAccountNotFoundException(String account) {
        super("Ledger account not found: " + account);
        this.account = account;
    }

    public String account() {
        return account;
    }
}
