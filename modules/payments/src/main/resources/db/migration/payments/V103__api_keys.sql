-- payments.api_keys (design.md §5.1, spec §3.2, §5.9) — Stripe-style API keys
-- Money is never stored here; this table binds credentials to merchants.
-- Expand-only: no column drops, no type narrows (design.md §5, D16).

CREATE TABLE payments.api_keys (
    id                  uuid PRIMARY KEY,              -- UUIDv7, app-generated
    merchant_id         uuid NOT NULL,
    name                varchar(80) NOT NULL,          -- human-readable label
    key_prefix          varchar(16) NOT NULL,          -- indexable prefix for fast lookup
    key_hash            varchar(64) NOT NULL,          -- SHA-256 hex of the full raw key
    created_at          timestamptz NOT NULL DEFAULT now(),
    revoked_at          timestamptz,                   -- null = active

    CONSTRAINT uq_api_keys_key_hash UNIQUE (key_hash)
);

-- Partial unique index for active keys by prefix (the hot lookup path)
-- Only one active (non-revoked) key per prefix
CREATE UNIQUE INDEX IF NOT EXISTS uq_api_keys_key_prefix_active
    ON payments.api_keys (key_prefix)
    WHERE revoked_at IS NULL;

-- Listing by merchant (admin/observability)
CREATE INDEX IF NOT EXISTS idx_api_keys_merchant_created
    ON payments.api_keys (merchant_id, created_at DESC);