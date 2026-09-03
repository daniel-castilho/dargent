package io.dargent.ledger.domain.exception;

/**
 * Raised when a {@link JournalEntry} violates the double-entry invariants:
 * <ul>
 *   <li>Σ DEBIT ≠ Σ CREDIT</li>
 *   <li>fewer than 2 postings</li>
 *   <li>any posting amount ≤ 0</li>
 * </ul>
 * This is the constructor-side barrier for DEBT-5 (AGENTS.md §8).
 */
public final class InvalidJournalEntryException extends RuntimeException {

    public InvalidJournalEntryException(String message) {
        super(message);
    }
}