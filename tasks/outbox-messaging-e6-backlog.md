# Outbox & Messaging Backbone E6 — Backlog

**Epic:** E6 — relay the outbox to SNS/SQS FIFO with DLQ and retention · **Milestone:** M2
**Spec:** `outbox-messaging-e6-spec.md` (contracts are binding) · **Sequence:** `outbox-messaging-e6-implementation-sequence.md`

## Story map (value left to right, risk top to bottom)

| → Deliver | → Survive | → Govern | → Prove |
|---|---|---|---|
| Relay publishes outbox rows to SNS FIFO (S2) | Retry with backoff, rows stay PENDING (S2) | Purge of SENT rows, retention knob (S5) | ITs 1–6 incl. M2 anchor E2E (S6) |
| FIFO topic + notify queue + DLQ redrive (S3) | SKIP LOCKED under overlap (S2/S4) | UUIDv7 row ids per V105 (S1) | Hygiene greps + matrix + flip (S7) |
| Compose + LocalStack dev topology (S3) | At-least-once statement, dedup contract (S2) | BoE sizing doc, derived defaults (S5) | — |
| **Step 0 (S0)** | TD-13 docs correction — the citation layer must tell the truth before E6 adds to it | | |

## Items

| ID | Item (spec §) | Acceptance anchor |
|---|---|---|
| S0 | **TD-13 correction (docs-only):** e3r matrix register ids fixed to the committed spec's numbering (kill the TD-9 off-by-one echo + rejected-paraphrase structure), BD-10…BD-14 and TD-7…TD-11 traceability rows added, every run number↔id pair reconciled against the canonical table in `e3r-block1-verification.md`, artifact index updated (e3 row + new E6 row), hygiene-grep outputs pasted with their commit id. No code, no re-flip | Spec §2 register ↔ matrix 1:1; every cited pair matches the table |
| S1 | `OutboxId` UUIDv7 VO + writers switched (§5.5) | Property tests green (version/variant/monotonic/uniqueness) |
| S2 | `OutboxDeliveryUseCase` + relay policy + `EventPublisher` port, pure TDD (§5.1, §5.2 retry) | Unit suite: claim/backoff/mark/parse-defect paths |
| S3 | `SnsEventPublisher` (AWS SDK v2) + compose LocalStack + `deploy/localstack-init.sh` (§4, §5.2) | Wiring bean exists; script idempotent (re-run no-ops); env table in `.env.example` |
| S4 | ITs 1–3: happy publish, retry deferral, two-thread SKIP LOCKED race (§6) | Test names + run pair cited; zero sleeps |
| S5 | Purge job + `docs/load-test-baseline.md` BoE section (§5.4, §5.7) | IT4 green; defaults in yaml cite the BoE |
| S6 | ITs 4–6: purge, **M2 anchor E2E** (create→confirm→queue message), topology attrs (§7) | IT5 is the headline cell of `e6-acceptance-matrix.md` |
| S7 | Closure: matrix built cell-by-cell, README/CHANGELOG truth-scoped entries, ledger **E6 flip last** with final run id | DoD §9 all checked; greps pasted with commit id |

## Explicitly out (do not absorb)
E10 consumer · E9 EXHAUSTED/requeue/republish · E5 reconciler · E7 ledger projection · E11 metrics ·
production AWS provisioning · k6 · any migration (V105 stands; drift = stop-and-report).

## Debt & adjacencies registered elsewhere
- TD-13 (citation layer) → S0 of THIS epic, before any code.
- UUIDv7-for-eventId → deferred option (ideas ledger §6) — needs owner decision + sizing signal (relay lag/ index bloat); do not implement opportunistically.
- DEV-R2-4 formal owner ratification — still open from Block 1; requested again at E3R close.
