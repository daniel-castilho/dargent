-- Refunds table (E8 spec §2)
-- Forward-only, expand-only (V112)

CREATE TABLE IF NOT EXISTS payments.refunds (
    id                 uuid PRIMARY KEY,
    payment_id         uuid        NOT NULL REFERENCES payments.payments (id),
    txid               varchar(25) NOT NULL,
    amount_cents       bigint      NOT NULL CHECK (amount_cents > 0),
    fee_reversal_cents bigint      NOT NULL CHECK (fee_reversal_cents >= 0),
    net_cents          bigint      NOT NULL CHECK (net_cents >= 0),
    request_id         varchar(64),
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_refund_net CHECK (net_cents = amount_cents - fee_reversal_cents)
);

CREATE INDEX IF NOT EXISTS idx_refunds_payment_created
    ON payments.refunds (payment_id, created_at DESC);