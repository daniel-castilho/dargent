-- Expand-only (AGENTS §3.8): relax journal_entries.event_id to NULL so settlement journal
-- entries (spec §5.5) — which carry an idempotency_key, not an envelope event — can be written.
-- Event-driven entries keep the NOT NULL UNIQUE reference to ledger.events (dedupe by event_id).
-- Owner decision: E7 S4 settlement journal entry divergence (amend c/d). txid stays NOT NULL.

ALTER TABLE ledger.journal_entries
    ALTER COLUMN event_id DROP NOT NULL;
