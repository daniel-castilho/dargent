ALTER TABLE payments.payments
    ADD COLUMN next_reconcile_at  timestamptz NULL,
    ADD COLUMN reconcile_attempts int NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_payments_pending_expires
    ON payments.payments (expires_at) WHERE status = 'PENDING';
