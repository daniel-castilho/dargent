-- notifications module owns schema "notifications" (design.md §5, decision D2).
CREATE SCHEMA IF NOT EXISTS notifications;

CREATE TABLE IF NOT EXISTS notifications.notification (
    id           UUID        PRIMARY KEY,
    event_id     UUID        NOT NULL,
    type         TEXT        NOT NULL,
    txid         TEXT        NULL,
    merchant_id  UUID        NOT NULL,
    payload      JSONB       NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_event UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_notification_merchant_created
    ON notifications.notification (merchant_id, created_at DESC, id DESC);
