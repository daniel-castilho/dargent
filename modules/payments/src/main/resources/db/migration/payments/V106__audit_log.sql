-- payments.audit_log (E3 spec §5.1, design.md §5.1) — minimal command audit trail
-- The "who" of commands; the "what" lives in aggregate events and raw webhooks.
-- Expand-only: no column drops, no type narrows (design.md §5, D16).

CREATE TABLE payments.audit_log (
    id                  uuid PRIMARY KEY,               -- UUIDv7
    command_name        varchar(64) NOT NULL,           -- e.g. CreatePayment
    actor_key_id        uuid NOT NULL,                  -- API key id
    merchant_id         uuid NOT NULL,
    aggregate_id        varchar(25),                    -- txid (nullable for failed creates)
    request_id          varchar(64),                    -- X-Request-Id
    created_at          timestamptz NOT NULL DEFAULT now()
);

-- Index for tenant-scoped audit queries
CREATE INDEX IF NOT EXISTS idx_audit_log_merchant_created
    ON payments.audit_log (merchant_id, created_at DESC);

-- Index for correlation by request
CREATE INDEX IF NOT EXISTS idx_audit_log_request
    ON payments.audit_log (request_id);