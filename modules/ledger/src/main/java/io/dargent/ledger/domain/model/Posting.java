package io.dargent.ledger.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Posting line in a journal entry (spec §5.2).
 * Direction uses the E0 enum EntryDirection (DEBIT/CR).
 */
public final class Posting {

    private final UUID id;
    private final UUID entryId;
    private final String account;
    private final EntryDirection direction;
    private final long amountCents;
    private final Instant createdAt;

    public Posting(UUID id, UUID entryId, String account, EntryDirection direction,
            long amountCents, Instant createdAt) {
        this.id = id;
        this.entryId = entryId;
        this.account = account;
        this.direction = direction;
        this.amountCents = amountCents;
        this.createdAt = createdAt;
    }

    public UUID id() { return id; }
    public UUID entryId() { return entryId; }
    public String account() { return account; }
    public EntryDirection direction() { return direction; }
    public long amountCents() { return amountCents; }
    public Instant createdAt() { return createdAt; }

    public long signedAmountCents() {
        return direction == EntryDirection.CREDIT ? amountCents : -amountCents;
    }
}