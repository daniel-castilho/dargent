package io.dargent.ledger.domain.model;

/**
 * Double-entry direction (design.md §5.2). Every journal closes: Σ DEBIT = Σ CREDIT —
 * append-only, corrections are reversing entries (AGENTS.md §3.5).
 */
public enum EntryDirection {
    DEBIT("DR"),
    CREDIT("CR");

    private final String code;

    EntryDirection(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
