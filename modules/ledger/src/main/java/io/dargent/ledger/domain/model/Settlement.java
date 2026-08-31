package io.dargent.ledger.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Settlement record (spec §5.5).
 * Pays out a merchant's available balance.
 */
public final class Settlement {

    private final UUID id;
    private final UUID merchantId;
    private final String idempotencyKey;
    private final long amountCents;
    private final UUID entryId;
    private final Instant settledAt;

    public Settlement(UUID id, UUID merchantId, String idempotencyKey,
            long amountCents, UUID entryId, Instant settledAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.amountCents = amountCents;
        this.entryId = entryId;
        this.settledAt = settledAt;
    }

    public UUID id() { return id; }
    public UUID merchantId() { return merchantId; }
    public String idempotencyKey() { return idempotencyKey; }
    public long amountCents() { return amountCents; }
    public UUID entryId() { return entryId; }
    public Instant settledAt() { return settledAt; }
}