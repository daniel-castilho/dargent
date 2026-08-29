-- payments.outbox (E3 spec §5.1, §5.6, design.md §7.4) — transactional outbox
-- Relay polls PENDING with next_attempt_at <= now() via partial index.
-- Expand-only: no column drops, no type narrows (design.md §5, D16).

CREATE TABLE payments.outbox (
    id                  uuid PRIMARY KEY,               -- UUIDv7
    aggregate_id        varchar(25) NOT NULL,           -- txid
    type                varchar(64) NOT NULL,           -- e.g. payment.created
    version             int NOT NULL,                   -- event version
    payload             jsonb NOT NULL,                 -- serialized EventEnvelope.payload
    request_id          varchar(64),                    -- X-Request-Id correlation
    status              varchar(16) NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING','SENT','FAILED','EXHAUSTED')),
    attempt_count       int NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz NOT NULL DEFAULT now(),
    created_at          timestamptz NOT NULL DEFAULT now(),
    published_at        timestamptz
);

-- Partial index for relay's poll (hot path)
CREATE INDEX IF NOT EXISTS idx_outbox_pending_due
    ON payments.outbox (next_attempt_at)
    WHERE status = 'PENDING';

-- Index for admin/replay by aggregate
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_created
    ON payments.outbox (aggregate_id, created_at DESC);