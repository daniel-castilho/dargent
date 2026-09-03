ALTER TABLE payments.payments
    ADD COLUMN next_reconcile_at  timestamptz NULL,
    ADD COLUMN reconcile_attempts int NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_payments_pending_expires
    ON payments.payments (expires_at) WHERE status = 'PENDING';

-- Reconciler scan (TD-21): status IN ('PENDING','EXPIRED') AND next_reconcile_at <= now().
CREATE INDEX IF NOT EXISTS idx_payments_reconcile_due
    ON payments.payments (next_reconcile_at) WHERE status IN ('PENDING', 'EXPIRED');

-- TD-21 backfill: existing open (PENDING) rows enter the reconciliation pipeline on the first rung
-- (owner decision 2026-09-02). NULL means "gave up / not scheduled", never "due now".
UPDATE payments.payments
SET next_reconcile_at = created_at + interval '60 seconds'
WHERE status = 'PENDING' AND next_reconcile_at IS NULL;
