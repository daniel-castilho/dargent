-- payments.payments table (design.md §5.1, spec §8) — module-owned DDL.
-- Money is bigint cents (D5); every transition is guarded by the version column via a
-- conditional UPDATE (D6); the domain guard is the first line, this row the last.
-- Blue-green expand/contract discipline (D16): forward-only, no rollback.
CREATE TABLE payments.payments (
    id                  uuid PRIMARY KEY,        -- UUIDv7, app-generated (D3)
    txid                varchar(25) NOT NULL UNIQUE, -- PIX cap, public id + ordering key (D4)
    merchant_id         uuid NOT NULL,
    description         varchar(140),            -- added by E1; nullable merchant note
    amount_cents        bigint NOT NULL CHECK (amount_cents > 0),
    status              varchar(32) NOT NULL
                        CHECK (status IN ('PENDING','CONFIRMED','PARTIALLY_REFUNDED',
                                          'REFUNDED','EXPIRED','FAILED')),
    version             int NOT NULL DEFAULT 0,  -- optimistic lock (D6 / @Version)
    expires_at          timestamptz NOT NULL,    -- copied from the PSP, never computed locally
    end_to_end_id       varchar(32),             -- null until confirmation
    fee_cents           bigint,                  -- null until confirmation
    net_cents           bigint,                  -- null until confirmation
    late_confirmation   boolean NOT NULL DEFAULT false, -- resurrection flag (D6)
    refunded_cents      bigint NOT NULL DEFAULT 0, -- aggregate-tracked remaining; ledger is truth in E7+
    created_at          timestamptz NOT NULL,
    confirmed_at        timestamptz
);

-- No other indexes in E1: the partial expiration index arrives with E5, the listing
-- index with E3's listing story (design.md §5.1).
