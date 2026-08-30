-- payments.audit_log: make actor_key_id nullable for webhook callbacks (E4 §5.3 step 7)
-- PSP callbacks have no API key; actor is null — not a fabricated UUID.
-- Expand-only: column widen (NOT NULL → NULL), no type narrow (design.md §5, D16).

ALTER TABLE payments.audit_log
    ALTER COLUMN actor_key_id DROP NOT NULL;