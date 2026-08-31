package io.dargent.ledger.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Balance projection account (spec §5.2, §5.4).
 * Credit-positive convention: credits increase balance, debits decrease.
 */
public final class Account {

    private final String account;
    private final long balanceCents;
    private final Instant updatedAt;
    private final UUID lastEventId;

    public Account(String account, long balanceCents, Instant updatedAt, UUID lastEventId) {
        this.account = account;
        this.balanceCents = balanceCents;
        this.updatedAt = updatedAt;
        this.lastEventId = lastEventId;
    }

    public String account() { return account; }
    public long balanceCents() { return balanceCents; }
    public Instant updatedAt() { return updatedAt; }
    public UUID lastEventId() { return lastEventId; }

    public Account credit(long amountCents, Instant now, UUID eventId) {
        return new Account(account, balanceCents + amountCents, now, eventId);
    }

    public Account debit(long amountCents, Instant now, UUID eventId) {
        return new Account(account, balanceCents - amountCents, now, eventId);
    }
}