-- The ingestion use case records each event's initial RECEIVED state before transitioning it to
-- POSTED / IGNORED / REJECTED in the same processing pass (spec §5.3, §5.7). The original CHECK
-- (V201) omitted RECEIVED, which made the real DB reject legitimate ingestion — only surfaced once
-- S5's integration tests exercised the actual schema (unit tests used a fake store). Forward-only:
-- relax the CHECK to admit the RECEIVED state.

ALTER TABLE ledger.events DROP CONSTRAINT events_status_check;
ALTER TABLE ledger.events ADD CONSTRAINT events_status_check
    CHECK (status IN ('RECEIVED','POSTED','IGNORED','REJECTED'));
