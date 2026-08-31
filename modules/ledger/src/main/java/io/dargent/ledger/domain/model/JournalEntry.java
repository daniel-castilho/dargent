package io.dargent.ledger.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Journal entry created from a posted event (spec §5.2).
 * Contains postings that must balance (Σ DEBIT = Σ CREDIT).
 */
public final class JournalEntry {

    private final UUID id;
    private final UUID eventId;
    private final String txid;
    private final UUID merchantId;
    private final String description;
    private final Instant createdAt;
    private final List<Posting> postings;

    public JournalEntry(UUID id, UUID eventId, String txid, UUID merchantId,
            String description, Instant createdAt, List<Posting> postings) {
        this.id = id;
        this.eventId = eventId;
        this.txid = txid;
        this.merchantId = merchantId;
        this.description = description;
        this.createdAt = createdAt;
        this.postings = List.copyOf(postings);
    }

    public UUID id() { return id; }
    public UUID eventId() { return eventId; }
    public String txid() { return txid; }
    public UUID merchantId() { return merchantId; }
    public String description() { return description; }
    public Instant createdAt() { return createdAt; }
    public List<Posting> postings() { return postings; }

    public long netAmountCents() {
        return postings.stream().mapToLong(Posting::signedAmountCents).sum();
    }
}