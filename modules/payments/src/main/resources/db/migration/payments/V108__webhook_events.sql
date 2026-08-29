-- payments.webhook_events (E4 spec §5.4) — raw webhook intake evidence
-- Every webhook call (valid or invalid) gets a row; raw payload is immutable evidence.
-- Expand-only: no column drops, no type narrows (design.md §5, D16).

CREATE TABLE payments.webhook_events (
    id                  uuid PRIMARY KEY,               -- UUIDv7, app-generated
    provider_event_id   varchar(96) NOT NULL UNIQUE,    -- endToEndId + "|" + type (dedupe key)
    psp_event_id        varchar(64),                    -- the PSP's own eventId (audit only)
    type                varchar(64) NOT NULL,           -- e.g. payment.confirmed
    txid                varchar(25),                    -- payment txid (nullable for unknown)
    payload_raw         jsonb NOT NULL,                 -- raw body bytes, UTF-8, immutable
    signature_valid     boolean NOT NULL,               -- true if HMAC verified; false = attack evidence
    status              varchar(16) NOT NULL
                            CHECK (status IN ('RECEIVED','PROCESSED','IGNORED')),
    received_at         timestamptz NOT NULL DEFAULT now(),
    processed_at        timestamptz                      -- null until PROCESSED/IGNORED
);

-- Index for audit trail: find events by txid, newest first
CREATE INDEX IF NOT EXISTS idx_webhook_events_txid_received
    ON payments.webhook_events (txid, received_at DESC);

-- Partial index for relay/admin: only RECEIVED rows needing attention
CREATE INDEX IF NOT EXISTS idx_webhook_events_received_status
    ON payments.webhook_events (received_at)
    WHERE status = 'RECEIVED';