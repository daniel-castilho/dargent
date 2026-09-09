-- Rail discriminator for the payment strategy (M5 S0)
-- Forward-only, expand-only (V113): default 'pix' backfills existing rows and keeps blue/green
-- compatible. The JPA entity does not map this column — the JdbcRailAssignmentPort owns it.

ALTER TABLE payments.payments ADD COLUMN rail VARCHAR(16) NOT NULL DEFAULT 'pix';