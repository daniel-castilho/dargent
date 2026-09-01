-- Ledger command audit trail (spec §5.6): the ledger keeps its own trail for mutating
-- API commands (settlement, rebuild) — no dependency on payments' audit table.
-- merchant_id nullable: rebuild is a system-wide op with no single merchant target.

CREATE TABLE IF NOT EXISTS ledger.audit_log (
    id          uuid        PRIMARY KEY,
    command     varchar(32) NOT NULL,
    actor_key   uuid        NOT NULL,
    merchant_id uuid,
    target      text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ledger_audit_merchant ON ledger.audit_log (merchant_id);
CREATE INDEX IF NOT EXISTS idx_ledger_audit_command ON ledger.audit_log (command);
