-- Ledger event ingestion + idempotency (spec §5.2)
-- event_id is the deduplication key (unique, matches outbox envelope eventId)

CREATE TABLE IF NOT EXISTS ledger.events (
    event_id        uuid        PRIMARY KEY,
    type            varchar(64) NOT NULL,
    txid            varchar(64) NOT NULL,
    merchant_id     uuid        NOT NULL,
    payload         jsonb       NOT NULL,
    status          varchar(16) NOT NULL CHECK (status IN ('POSTED','IGNORED','REJECTED')),
    note            text,
    received_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ledger_events_type ON ledger.events (type);
CREATE INDEX IF NOT EXISTS idx_ledger_events_txid ON ledger.events (txid);
CREATE INDEX IF NOT EXISTS idx_ledger_events_merchant ON ledger.events (merchant_id);
CREATE INDEX IF NOT EXISTS idx_ledger_events_received ON ledger.events (received_at);

-- Journal entries — one per posted event (spec §5.2)
CREATE TABLE IF NOT EXISTS ledger.journal_entries (
    id              uuid        PRIMARY KEY,
    event_id        uuid        NOT NULL UNIQUE REFERENCES ledger.events (event_id),
    txid            varchar(64) NOT NULL,
    merchant_id     uuid        NOT NULL,
    description     text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ledger_journal_txid ON ledger.journal_entries (txid);
CREATE INDEX IF NOT EXISTS idx_ledger_journal_merchant ON ledger.journal_entries (merchant_id);
CREATE INDEX IF NOT EXISTS idx_ledger_journal_created ON ledger.journal_entries (created_at);

-- Postings — double-entry lines belonging to a journal entry (spec §5.2)
CREATE TABLE IF NOT EXISTS ledger.postings (
    id              uuid        PRIMARY KEY,
    entry_id        uuid        NOT NULL REFERENCES ledger.journal_entries (id),
    account         text        NOT NULL,
    direction       varchar(6)  NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ledger_postings_entry ON ledger.postings (entry_id);
CREATE INDEX IF NOT EXISTS idx_ledger_postings_account ON ledger.postings (account);
