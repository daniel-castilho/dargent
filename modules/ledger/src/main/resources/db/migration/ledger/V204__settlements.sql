-- Settlements (spec §5.5): full available balance per merchant, idempotent by key

CREATE TABLE IF NOT EXISTS ledger.settlements (
    id              uuid            PRIMARY KEY,
    merchant_id     uuid            NOT NULL,
    idempotency_key varchar(64)     NOT NULL UNIQUE,
    amount_cents    bigint          NOT NULL CHECK (amount_cents > 0),
    entry_id        uuid            NOT NULL REFERENCES ledger.journal_entries (id),
    settled_at      timestamptz     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ledger_settlements_merchant ON ledger.settlements (merchant_id);
