-- Balance projection — credit-positive convention (spec §5.2, §5.4)
-- Updated in the same transaction as journal write

CREATE TABLE IF NOT EXISTS ledger.balances (
    account         text        PRIMARY KEY,
    balance_cents   bigint      NOT NULL DEFAULT 0,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    last_event_id   uuid
);

-- Proof query (spec §5.4): 
-- (a) global Σ DEBIT = Σ CREDIT over ledger.postings
-- (b) per account: balance_cents == Σ credits - Σ debits
-- (c) every journal_entry has ≥ 2 postings
