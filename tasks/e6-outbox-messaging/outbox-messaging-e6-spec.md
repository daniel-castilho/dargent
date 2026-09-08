# Outbox & Messaging Backbone E6 — Technical Specification

## Epic E6 — "The event leaves the database": relay the outbox to SNS/SQS FIFO with DLQ and retention

**Priority:** P0 — the full money loop currently stops at `payments.outbox`: `payment.created`/`payment.failed`/
`payment.confirmed` rows land `PENDING` and nothing ever delivers them. Milestone M2's anchor (an event on a
queue) does not exist.
**Companions:** `outbox-messaging-e6-backlog.md` · `outbox-messaging-e6-implementation-sequence.md` ·
`ai-software-engineer-prompt-outbox-messaging-e6.md`
**Baseline:** main `f530b18` (docs-only governance push, parent `3b60ba8`); last code-evidencing run #30
(`33333739409`) green on `3b60ba8`. E3R closed (flips verified TRUE); **TD-13 (citation-layer correction) is
outstanding and is this epic's Step 0** — E6 code never starts before the docs tell the truth.

> **Driving principle (unchanged, binding):** a green CI proves that tests pass — not that they are right, and
> not that the code exists. Evidence is a test that runs in CI, cited by test name + run id (number AND id,
> the pair verified via API — the TD-13 lesson).

---

## 1. Purpose

E3 and E4 write transactional outbox rows correctly. E6 is the half of the pattern that makes them worth
writing: a relay that publishes them to the messaging backbone, a dead-letter path, and the retention
discipline that keeps the table from growing forever. Back-of-envelope arithmetic comes BEFORE the knob
values: workers, poll rate, batch size, and retention days are **derived** in §5.8, not guessed.

Delivery semantics are **at-least-once**: FIFO content deduplication collapses the in-window duplicates, and
consumer-side idempotency by `eventId` is a stated contract for E10 — never an E6 assumption of exactly-once.

## 2. Current state (verified in the tree @`3b60ba8` — line-level, not from memory)

- `V105__outbox.sql` **already carries the full delivery state machine** (this is why E6 needs ZERO migrations):
  `status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','FAILED','EXHAUSTED'))`,
  `attempt_count int NOT NULL DEFAULT 0`, `next_attempt_at timestamptz NOT NULL DEFAULT now()`,
  `published_at timestamptz`, partial index `idx_outbox_pending_due ON payments.outbox (next_attempt_at)
  WHERE status = 'PENDING'` (the relay's hot path), `idx_outbox_aggregate_created` for admin/replay, and the
  column comment `id uuid PRIMARY KEY -- UUIDv7` (see §5.6 — code does not honor it yet).
- Writers exist and land `PENDING`: create path (`payment.created`, `payment.failed` — E3) and webhook intake
  (`payment.confirmed` — E4), all through the `OutboxWriter` port + JDBC adapter, payload = the shared
  Jackson-3-serialized envelope (E3 §5.2 shape).
- **Nothing reads the table.** No `software.amazon.awssdk` import exists anywhere; compose has no LocalStack;
  `.env.example` has no relay/events vars. E6 creates all of it.

## 3. Scope

### In scope
- Relay engine in `modules/payments` (claim → publish → mark, `SKIP LOCKED`), hosted by `apps/api` behind a
  flag, safe under future blue-green overlap (E12);
- SNS publisher adapter (AWS SDK v2, direct — no framework), FIFO topic + FIFO queue + DLQ with redrive,
  provisioned for dev by an idempotent LocalStack init script in compose;
- Retention: scheduled purge of `SENT` rows (the liquidslr nugget: mandatory, was missing from the brief);
- `docs/load-test-baseline.md` seeded with the §5.8 BoE section (assumptions-arithmetic, honestly labeled —
  k6 measurements come later, stretch);
- ITs on real PG16 + LocalStack Testcontainers; matrix + ledger flip.

### Out of scope
- Consumers: E10 owns the notifications consumer (E6 delivers the queue + states its idempotency contract);
- `EXHAUSTED` terminal state, admin requeue/republish, delivery-hardening policy: **E9** (V105's CHECK
  reserves the value; E6 leaves it unused — rows stay `PENDING` until delivered);
- Expiration/reconciliation (E5), ledger projection (E7), metrics depth (E11), production AWS provisioning
  and deploy (E12/E14), k6 (stretch).

## 4. Architectural constraints

| Rule | Content |
|---|---|
| Messaging stack (decided 2026-08-28, §5 of the ideas ledger) | **AWS SDK v2 direct** (`software.amazon.awssdk:sns`, `software.amazon.awssdk:sqs`, url-connection HTTP client), adapters ours. **Never** Spring Cloud AWS, Spring Cloud Stream, Kafka, Debezium |
| New dependencies authorized HERE and only here | The two AWS SDK modules (prod, `modules/payments`) + `org.testcontainers:localstack` (test scope). Anything else = stop-and-report |
| Placement | `application/OutboxDeliveryUseCase` + relay policy (pure, TDD); `adapter/out/messaging/SnsEventPublisher` (port `EventPublisher`); relay read methods join the existing outbox JDBC adapter; loop host + wiring in `apps/api` (inbound/app-layer convention: boot app hosts, module owns logic). No new Maven module |
| Concurrency arbitration | `SELECT … FOR UPDATE SKIP LOCKED` only. No ShedLock, no Redis, no leader election |
| Host embedding | Relay runs inside `apps/api`, gated by `DARGENT_RELAY_ENABLED` (default `false` — tests and green runs unaffected). Blue-green overlap is safe: claim arbitration is the row locks; duplicates collapse on dedup (§6) |
| Migrations | **Zero.** V105 stands. If S0 finds any divergence between V105 and this spec → stop-and-report (schema↔spec rule, born BD-14) |
| Time & retry | Injected `Clock` + sleeper; zero `Thread.sleep` in prod or tests; the relay's unit of work is exposed as `runOnce()` so tests drive cycles deterministically |
| Parsing | The eventId is read from `payload` with the injected Jackson 3 ObjectMapper, strict (lesson #13 — no `extractJsonField`, ever). The published body is the **stored jsonb text verbatim** — never re-serialized |
| Sources | 100% English identifiers/comments/logs |

### 4.1 Config surface — env names are contract (new names land here and never change)

| Env | Default | Meaning |
|---|---|---|
| `DARGENT_RELAY_ENABLED` | `false` | Relay loop runs when true (compose + IT profile only) |
| `DARGENT_RELAY_WORKERS` | `2` | Concurrent claim workers (§5.8 derivation) |
| `DARGENT_RELAY_POLL_MS` | `1000` | Poll interval of the loop |
| `DARGENT_RELAY_BATCH` | `32` | Max rows claimed per `runOnce()` per worker |
| `DARGENT_OUTBOX_RETENTION_DAYS` | `7` | Purge age for `SENT` rows (§5.5) |
| `DARGENT_EVENTS_PUBLISH_TIMEOUT_MS` | `2000` | Per-message SNS call timeout |
| `DARGENT_EVENTS_TOPIC_ARN` | (dev: `arn:aws:sns:us-east-1:000000000000:dargent-payments-events.fifo`) | SNS FIFO topic |
| `DARGENT_EVENTS_QUEUE_URL` | (dev: `http://localstack:4566/000000000000/dargent-payments-notify.fifo`) | Notify queue (IT asserts attrs; consumer = E10) |
| `AWS_REGION` / `AWS_ENDPOINT_URL` | `us-east-1` / (dev: `http://localstack:4566`) | SDK target; dummy creds `test/test` in compose only |

`.env.example` gains every row above. `PSP_*`/`CHAOS_*` untouched.

## 5. Exact contracts

### 5.1 Relay cycle (`OutboxDeliveryUseCase.runOnce()` — one worker, one pass)

1. **Claim:** `SELECT id, aggregate_id, type, payload, request_id, attempt_count FROM payments.outbox WHERE
   status = 'PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP
   LOCKED` (uses `idx_outbox_pending_due`; uses the partial index, zero contention with the other worker).
2. **Parse:** extract `eventId` from `payload` via strict Jackson (`readTree`; missing/blank eventId = defect →
   leave row, log ERROR with `id`+`type` — a row without eventId is a writer bug, registered, never dropped).
3. **Publish:** SNS `publish` to `DARGENT_EVENTS_TOPIC_ARN` — `MessageGroupId = aggregate_id` (per-payment
   ordering), `MessageDeduplicationId = eventId`, `Subject = type`, body = stored `payload::text` verbatim,
   timeout `DARGENT_EVENTS_PUBLISH_TIMEOUT_MS`.
4. **Mark:** `UPDATE payments.outbox SET status='SENT', attempt_count = attempt_count + 1, published_at = now()
   WHERE id = :id AND status = 'PENDING'` — 0 rows updated (lost race) → log + move on. Publish-then-mark is
   deliberate: a crash between the two yields a duplicate (collapsed by FIFO dedup in-window; E10 idempotency
   beyond) — never a silent loss.
5. **On publish error:** `UPDATE payments.outbox SET attempt_count = attempt_count + 1, next_attempt_at = now()
   + :backoff WHERE id = :id AND status = 'PENDING'`; backoff by attempt_count: 1→30 s, 2→2 min, ≥3→5 min
   (cap). Row stays `PENDING` — E6 has no terminal state (E9 owns `EXHAUSTED`).
6. Commit per batch. The whole cycle is loggable: rows claimed/published/deferred with `request_id` correlation.

### 5.2 Topic / queue / DLQ (provisioned by `deploy/localstack-init.sh`, idempotent, compose-run)

- Topic `dargent-payments-events.fifo` (ContentBasedDeduplication not relied on — we always send
  `MessageDeduplicationId`).
- Queue `dargent-payments-notify.fifo` + DLQ `dargent-payments-notify-dlq.fifo`; subscription topic→queue;
  **RedrivePolicy `maxReceiveCount = 5`** on the notify queue. `FifoQueue = true` on both.
- The script is dev infrastructure; production provisioning is E14/ops. ITs assert attributes via
  `GetQueueAttributes`, never assume.

### 5.3 Wire format

The message body is the envelope exactly as stored (E3 §5.2 shape; Jackson-3, deterministic key order at
write time). No producer-side transformation for the queue — the stored envelope IS the wire format.

### 5.4 Retention / purge (governance — the nugget that was missing from the brief)

Every Nth cycle (N = 60 ≈ 1 min at default poll): `DELETE FROM payments.outbox WHERE id IN (SELECT id FROM
payments.outbox WHERE status = 'SENT' AND published_at < now() - make_interval(days =>
:DARGENT_OUTBOX_RETENTION_DAYS) LIMIT 1000)` in batches; one log line with rows deleted. `PENDING`,
`FAILED`, `EXHAUSTED` rows are never purged by E6 (ops visibility; E9 owns their lifecycle).

### 5.5 Outbox row id = UUIDv7 (V105's comment already declares it)

New pure VO `OutboxId` (domain) generating UUIDv7 (48-bit ms epoch + version/variant bits per RFC 9562) from
the injected clock. Writers switch to it. Property tests: version nibble = 7, variant = 10, monotonic
non-decreasing under a fixed clock, uniqueness across 10k. **The envelope `eventId` stays v4** — time-ordered
eventId is the documented option (ideas ledger §6), not decided; do not smuggle it in.

### 5.6 Delivery guarantee statement (goes in the code's javadoc AND the matrix)

At-least-once, per-payment FIFO ordering, dedup by `MessageDeduplicationId=eventId` (5-min FIFO window),
consumer idempotency by `eventId` = E10's binding contract. Nobody in this repo ever writes "exactly once".

### 5.7 Back-of-envelope (lands as the first section of `docs/load-test-baseline.md`, labeled "assumptions, not measurements")

| Premise (assumed, stated) | Value | Consequence |
|---|---|---|
| Steady payments/day | 50 000 | ×2 events ≈ 100 000 events/day ≈ **1.16 evt/s avg** |
| Peak multiplier | 20× | ≈ **23 evt/s peak sustained** |
| Envelope size | ~1 KB (jsonb + indexes ≈ 2–3×) | ~100 MB/day table growth ungoverned ≈ **3 GB/month** → purge is not optional |
| Relay ceiling | workers 2 × batch 32 / poll 1 s | **64 evt/s** ≫ peak with 2.7× headroom (hence `POLL_MS=1000`, not 5000) |
| Outage drain (RPO ≤ 15 min) | 1 h outage ≈ 4 200 events / 64 evt/s | **~66 s to drain** ✓ |
| Steady table size | 7-day retention | ~700 MB rows + ~1.4 GB indexes — bounded ✓ |
| SQS FIFO throughput | 300 TPS base | 23 evt/s peak: no high-throughput mode needed |

Knob defaults in §4.1 cite this table. Changing a default without re-deriving = spec violation.

## 6. Concurrency & races (all proven by tests)

| Race | Arbitration | Proof |
|---|---|---|
| Two relay workers / blue-green overlap claim the same row | `FOR UPDATE SKIP LOCKED` + conditional `status='PENDING'` mark | IT: 2 threads × same seeds → each row exactly one SENT, no double-mark |
| Crash between publish and mark | At-least-once + FIFO content dedup (eventId) | Unit: publisher succeeds, mark fails → next cycle republishes; dedup contract asserted on MessageDeduplicationId |
| Purge vs relay | Disjoint statuses (SENT vs PENDING) + conditional updates | IT4 asserts untouched PENDING |
| SNS down | Backoff 30 s/2 m/5 m, row stays PENDING, partial index stays hot | IT2: broken topic ARN → attempt_count=1, next_attempt_at ≈ +30 s |
| Row without eventId | Defect path: leave + ERROR log (writer bug, register) | Unit |

## 7. Testing requirements

- **Unit (pure, TDD, fakes — no Spring):** claim policy (due filter, batch cap, skip-locked via fake),
  backoff schedule, mark semantics (conditional), purge cutoff with injected `Clock`, `OutboxId` v7
  properties, eventId parse failure path.
- **ITs (PG16 + LocalStack Testcontainers; `runOnce()` driven, zero sleeps):**
  - IT1 happy: seed rows → `runOnce()` → message received from the notify queue with body semantically equal
    to the seeded payload (and byte-equal to the seeded jsonb text), `MessageGroupId = txid`,
    `MessageDeduplicationId = eventId`; row `SENT` + `published_at`.
  - IT2 retry: unpublishable row → `attempt_count=1`, `next_attempt_at ≈ now+30 s`, status stays `PENDING`.
  - IT3 race: two threads `runOnce()` over shared seeds → no loss, no double-SENT.
  - IT4 purge: old SENT deleted; fresh SENT + any PENDING kept.
  - IT5 **M2 anchor end-to-end:** create payment (API) → simulator pay → webhook confirm → `runOnce()` →
    `payment.confirmed` message on the queue. This is the cell the ledger's M2 has been waiting for.
  - IT6 topology: `FifoQueue=true` both queues, redrive `maxReceiveCount=5`, subscription exists.
- **Hygiene gates (closing):** no `com.springframework.cloud.*`/kafka imports; AWS SDK imports confined to
  `adapter/out/messaging/`; no `Thread.sleep`; no `String.format` JSON; env table complete in `.env.example`;
  grep outputs pasted with the commit id they ran at.

## 8. Risks & troubleshooting

| Risk | Mitigation |
|---|---|
| LocalStack drift vs real AWS | ITs assert the contract subset (attrs, ordering, dedup id) — not LocalStack internals; production provisioning is a later epic's stop-and-report surface |
| Row locks held across SNS calls | Bounded: batch ≤32 × timeout 2 s; purge/relay disjoint; acceptable at §5.7 volumes — revisit only with measurements |
| SDK internal retry storms amplifying an outage | Per-call timeout override + backoff already at DB level; document the layering |
| jsonb `::text` formatting surprises | IT1 asserts byte-equality against the seeded text — caught in test, not prod |
| Someone "fixes" at-least-once into exactly-once claims | §5.6 statement + matrix wording audited at close |
| V105 drift discovered in S0 | Stop-and-report (schema↔spec rule) — do not improvise a migration |

## 9. Closure checklist (epic DoD)

- [ ] TD-13 correction landed (Step 0) BEFORE any E6 code commit
- [ ] Relay per §5.1 with ITs 1–6 green in CI (test name + run number+id pairs, API-verified)
- [ ] Purge + retention landed (IT4) with `DARGENT_OUTBOX_RETENTION_DAYS` honored
- [ ] `docs/load-test-baseline.md` BoE landed; defaults derived from it
- [ ] `OutboxId` UUIDv7 live in all writers (comment no longer a lie)
- [ ] `.env.example` complete; compose + localstack-init script idempotent
- [ ] Hygiene greps pasted with their commit id; scope diff = 0 (no `modules/ledger`, `modules/notifications`, `apps/psp-simulator`)
- [ ] Matrix zero pending; **ledger E6 flip is the LAST commit**, final run id cited at that HEAD
- [ ] E5 unblocks (E3R already unblocked it on paper — E6 was the queue)
