-- payments.idempotency_keys (E3 spec §3.2, §5.6) — per-tenant, per-endpoint idempotency
-- PK is composite (merchant_id, idempotency_key, endpoint) — a deliberate refinement of
-- design.md's "key unique" to support multiple endpoints per tenant.
-- Expand-only: no column drops, no type narrows (design.md §5, D16).

CREATE TABLE payments.idempotency_keys (
    merchant_id         uuid NOT NULL,
    idempotency_key     varchar(64) NOT NULL,
    endpoint            varchar(64) NOT NULL,           -- e.g. "POST /v1/payments"
    request_fingerprint varchar(64) NOT NULL,           -- SHA-256 hex of canonical request body
    state               varchar(16) NOT NULL
                            CHECK (state IN ('IN_FLIGHT','COMPLETED')),
    payment_txid        varchar(25),                    -- set on COMPLETED
    response_status     int,                            -- set on COMPLETED
    response_body       jsonb,                          -- snapshot of response body
    created_at          timestamptz NOT NULL DEFAULT now(),
    completed_at        timestamptz,

    CONSTRAINT pk_idempotency_keys PRIMARY KEY (merchant_id, idempotency_key, endpoint)
);

-- Index for cleanup job (old IN_FLIGHT rows)
CREATE INDEX IF NOT EXISTS idx_idempotency_keys_in_flight_age
    ON payments.idempotency_keys (created_at)
    WHERE state = 'IN_FLIGHT';