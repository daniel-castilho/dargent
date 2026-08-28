-- ledger module owns schema "ledger" (design.md §5.2, decision D2).
-- journal_entries / ledger_entries / balances arrive with M2 — append-only from birth (D10).
CREATE SCHEMA IF NOT EXISTS ledger;
