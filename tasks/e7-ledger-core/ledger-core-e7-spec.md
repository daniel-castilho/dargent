# Ledger Core E7 — Technical Specification

## Epic E7 — "Money leaves a trail": double-entry journal, balance projection, proof, settlement

**Priority:** P0 — `payment.confirmed` events now reach `dargent-payments-notify.fifo` (E6 IT5), but nothing
accounts for them. The ledger module is an empty skeleton. Milestone M2 completes with E7 + E10.
**Companions:** `ledger-core-e7-backlog.md` · `ledger-core-e7-implementation-sequence.md` ·
`ai-software-engineer-prompt-ledger-core-e7.md` · Block 1 execution prompt:
`ledger-core-e7-execution-prompt-block1.md`
**Baseline:** main `e6d8751`; run #48 (`33355665328`) green. E6 flip `084bb3a` (#47 `33355346290`).
**Step 0 = TD-13 residual** (docs-only; register row lists exactly what survived E6's S0) — E7 never builds
on half-corrected docs.

> **Driving principle (unchanged):** a green CI proves that tests pass — not that they are right, and not
> that the code exists. Evidence is a CI test cited by name + run number AND id, pair API-verified.

---

## 1. Purpose

The outbox publishes facts; the ledger owns their financial meaning: an immutable double-entry journal, a
balance projection maintained in the same transaction as the journal, a **proof** that projection equals
journal (plus the global debits==credits invariant), and settlement that pays a merchant's available balance
out. The journal is a financial record: **append-only, never purged** (the deliberate contrast with the
outbox's 7-day retention).

## 2. Current state (verified facts — re-verify from your tree at S0; drift = stop-and-report)

- `modules/ledger` exists as an E0 skeleton (own Flyway per-module convention); **zero business code**.
  List `modules/ledger/src/main/resources/db/migration/` via contents API before assuming its shape
  (raw-guessing paths 404s — audited lesson).
- E6 delivered (its matrix is the evidence): SNS topic + `dargent-payments-notify.fifo` + DLQ + relay
  marking rows SENT; `payment.confirmed` payload = `{amount, fee, net, late}` inside the E3 §5.2 envelope;
  `MessageGroupId = txid`, `MessageDeduplicationId = eventId`; consumer idempotency-by-`eventId` is the
  stated contract every consumer must honor locally.
- Stack law for this module: **JDBC via `JdbcClient` — no JPA, no entities, no Hibernate** (decided
  2026-08-28). Records in, SQL out; the use case sits behind a port, fakes for units, contract ITs for SQL.

## 3. Scope

### In scope
- Ledger schema (new per-module migrations), ingestion + posting use cases (TDD), SQS FIFO consumer for a
  **second queue** (fan-out — the notify queue stays E10's), balance projection + proof, settlement (MVP:
  full available balance per merchant), minimal HTTP read/settle surface in `apps/api`, BoE addendum,
  matrix + ledger flip.
- Authorized new dependencies: `software.amazon.awssdk:sqs` (prod, `modules/ledger` only — `sns`/`sqs`
  testcontainers deps already exist from E6). **Anything else = stop-and-report.**

### Out of scope
- E10's notifications consumer (separate queue, separate epic); E8 refunds/reversals (no posting reverses
  another in E7); fees beyond the fixed 100 bps recorded fact; multi-currency (BRL-only); archival of the
  journal (E14 concern — journal grows, documented in §5.8); any change to `modules/payments` prod sources
  (the relay is not touched); k6; production AWS (E14/ops).

## 4. Architectural constraints

| Rule | Content |
|---|---|
| Module boundaries | `modules/ledger` imports NOTHING from `modules/payments` (and vice versa). The SNS message body is the contract — ledger owns a local strict `EventEnvelopeReader` (Jackson 3). Boundary greps at S7 |
| Access | `JdbcClient` only; one JDBC adapter class implementing the `LedgerStore` port; no second table-access path |
| Placement | `domain/model` (Posting, JournalEntry, Settlement, Account), `application/EventIngestionUseCase`, `SettlementUseCase`, port `domain/port/out/LedgerStore`; `adapter/out/db/JdbcLedgerStore`, `adapter/out/messaging/SqsEventConsumer` (SQS imports confined there); consumer loop host + controllers in `apps/api` (inbound HTTP + hosting = boot app, DEV-R6 convention) |
| Consumer hosting | `apps/api`, gated by `DARGENT_LEDGER_CONSUMER_ENABLED` (default `false`), same `ThreadPoolTaskScheduler` + `SmartLifecycle` + `runOnce()` pattern as the E6 relay; no new app, no Reactor |
| Time | Injected `Clock`; zero `Thread.sleep`; zero `Instant.now()` in prod request paths |
| Migrations | New files under `modules/ledger/src/main/resources/db/migration/ledger/` (`V101__journal.sql`, `V102__balances.sql`, `V103__settlements.sql`), expand-only, forward-only. **Zero edits to payments migrations, ever** |
| Env names are contract | New names only from §4.1; `PSP_*`/`CHAOS_*`/`DARGENT_RELAY_*`/`DARGENT_EVENTS_*` untouched |

### 4.1 Config surface (new rows; land verbatim in `.env.example`)

| Env | Default | Meaning |
|---|---|---|
| `DARGENT_LEDGER_CONSUMER_ENABLED` | `false` | SQS consumer loop runs when true |
| `DARGENT_LEDGER_QUEUE_URL` | (dev: `http://localstack:4566/000000000000/dargent-payments-ledger.fifo`) | Ledger fan-out queue |
| `DARGENT_LEDGER_POLL_MS` | `1000` | Poll interval between empty receives |
| `DARGENT_LEDGER_BATCH` | `10` | SQS `maxNumberOfMessages` (SQS hard max = 10) |

Reuses `AWS_REGION` / `AWS_ENDPOINT_URL` from E6. That is the complete list — additions = stop-and-report.

## 5. Exact contracts

### 5.1 Queue topology (fan-out — extends `deploy/localstack-init.sh`, still idempotent)

New queue `dargent-payments-ledger.fifo` (`FifoQueue=true`) + `dargent-payments-ledger-dlq.fifo`;
subscription `dargent-payments-events.fifo` → ledger queue; **RedrivePolicy `maxReceiveCount = 5`**. The
notify queue/DLQ from E6 are untouched. The script re-run must no-op (S3 proves it by executing twice).

### 5.2 Schema (essence; DDL exact in the migration files, expand-only)

- `ledger.events` — ingestion + idempotency: `event_id uuid PRIMARY KEY`, `type`, `txid`, `merchant_id`,
  `payload jsonb`, `status CHECK (status IN ('POSTED','IGNORED','REJECTED'))`, `note text` (why
  ignored/rejected), `received_at`. **Dedupe = `INSERT … ON CONFLICT (event_id) DO NOTHING`**: 0 rows
  inserted ⇒ duplicate ⇒ ack + skip (the local idempotency the E6 contract demands).
- `ledger.journal_entries` — `id uuid PK`, `event_id uuid UNIQUE REFERENCES ledger.events` (nullable for
  settlement entries which carry an `idempotency_key` instead of an envelope event — V205), `txid`,
  `merchant_id`, `description`, `created_at`.
- `ledger.postings` — `id uuid PK`, `entry_id uuid REFERENCES ledger.journal_entries`, `account text`,
  `direction CHECK (direction IN ('DEBIT','CREDIT'))`, `amount_cents bigint CHECK (amount_cents > 0)`,
  `created_at`.
- `ledger.balances` — `account text PRIMARY KEY`, `balance_cents bigint NOT NULL` (signed: **credit
  positive**), `updated_at`, `last_event_id uuid` — updated in the SAME tx as the journal write.
- `ledger.settlements` — `id uuid PK`, `merchant_id`, `idempotency_key varchar(64) UNIQUE`,
  `amount_cents bigint CHECK (amount_cents > 0)`, `entry_id uuid REFERENCES ledger.journal_entries`,
  `settled_at`.

### 5.3 Posting rules (the only money-math in the epic — encode exactly)

| Event type | Journal | Postings (amounts from payload) |
|---|---|---|
| `payment.confirmed` | POSTED | DEBIT `payments:processing` `amount`; CREDIT `fees:revenue` `fee`; CREDIT `merchant:{merchantId}:available` `net` — invariant `fee + net == amount` must hold |
| `payment.created` | IGNORED (note: `pending — no money moved`) | none |
| `payment.failed` | IGNORED (note: `failed — no money moved`) | none |
| unknown type | IGNORED (note: `unknown type`) | none |
| payload violating `fee + net == amount` | **REJECTED** + ERROR log | none — a lying event is never partially posted; REJECTED rows are an ops alarm |

Structurally **unparsable** envelope = poison: do NOT ack; SQS redrive (5 receives) lands it in the ledger
DLQ — the app stays green and the message is preserved for ops. Parsable-but-wrong (unknown type, invariant
violation) = recorded + acked. This distinction is binding and IT-proven (§7 IT6 vs IT3/IT5).

### 5.4 Balances projection & proof

- Projection update per posting batch, same tx: `balance_cents += CASE direction WHEN 'CREDIT' THEN
  amount_cents ELSE -amount_cents END` upserted per account.
- **Proof** (one SQL, no shortcuts): (a) global `sum(DEBIT) == sum(CREDIT)` over `ledger.postings`; (b)
  per account: `ledger.balances.balance_cents == sum(credits) - sum(debits)`; (c) every `journal_entry` has
  ≥ 2 postings. Exposed as `GET /v1/ledger/proof` → `200 {"ok":true, accountsChecked, entriesChecked,
  postingsChecked}` or `200 {"ok":false, firstDivergence:{…}}` (a diagnostic, not an error path).
- **Rebuild-from-journal** (projection is disposable, journal is truth): `POST /v1/ledger/rebuild` —
  recompute `ledger.balances` from postings in one tx; IT4 proves corrupt→rebuild→proof-ok.

### 5.5 Settlement (MVP: full available balance)

`POST /v1/ledger/settlements` with `Idempotency-Key` + `{merchantId}` → one tx: read available balance
(≤ 0 → `409 no_balance_to_settle`); insert settlement row (idempotency_key conflict → return the existing
settlement, `Idempotent-Replay: true` — E3's semantics, small scale); journal entry + postings (DEBIT
`merchant:{id}:available` `amount`, CREDIT `payouts:external` `amount`); balances updated. Response `201`
with settlement body. Partial settlements: out of scope (E9/E8 revisit).

### 5.6 HTTP surface (controllers in `apps/api`, authenticated API key, same error contract)

| Route | Meaning |
|---|---|
| `GET /v1/ledger/accounts/{account}/balance` | `{account, balanceCents, updatedAt}`; unknown account → `404 account_not_found` |
| `GET /v1/ledger/proof` | §5.4 diagnostic |
| `POST /v1/ledger/rebuild` | §5.4 rebuild (authenticated; audit row via `LedgerStore`) |
| `POST /v1/ledger/settlements` | §5.5 |

Routes exist in `SecurityConfig` explicitly (endpoint-without-rule = violation). Settlement/rebuild write an
`audit`-style row through the store (command, actor key id, target) — the ledger keeps its own trail; no
dependency on payments' audit table.

### 5.7 Delivery guarantee statement (javadoc + matrix verbatim)

Ingestion is **at-least-once + local dedupe by `event_id`** (unique PK); posting is exactly-one-per-event
**by construction** (journal write shares the dedupe tx); ordering per payment is FIFO-delivered but the
ledger does not depend on it (idempotent math). Nobody writes "exactly once".

### 5.8 BoE addendum (appends to `docs/load-test-baseline.md`, assumptions labeled)

Confirmed subset ≈ all events at MVP volume: 100k events/day → ~100k journal entries + ~300k postings
(3/confirmed-avg) ≈ 40 MB/day journal+indexes — permanent (no purge) → ~1.5 GB/month: acceptable now,
archival is E14's row. Proof query is O(postings) — fine at this size, revisit with measurements.

## 6. Concurrency & races (proven by tests)

| Race | Arbitration | Proof |
|---|---|---|
| Duplicate delivery (SQS at-least-once) | `INSERT … ON CONFLICT (event_id) DO NOTHING` first statement of the tx | IT2: same message twice → one entry, second ack-skips |
| Two consumers / redelivery while processing | SQS visibility timeout + DB dedupe as backstop | IT3 loop variant or unit (conflict path) |
| Settlement vs confirm racing on the same merchant balance | Balance read + postings + balance write inside ONE tx with `SELECT … FOR UPDATE` on the `ledger.balances` row | IT5b: concurrent settle + confirm → both land, proof stays green, no lost update |
| Consumer crash between receive and ack | Message re-delivered → dedupe skip | IT2 variant with a thrown ack failure |
| Rebuild vs consumer writing | `FOR UPDATE` on balances rows during rebuild; consumer txs serialize on the same rows | IT4b asserts no partial state (or documented scheduling via runOnce determinism) |

## 7. Testing requirements

- **Unit (pure, fakes, no Spring):** envelope reader (strict; missing/blank eventId → poison; unknown type →
  IGNORED; invariant violation → REJECTED), posting math (incl. `fee + net == amount`), dedupe outcome
  branches, settlement guards (zero/negative balance, idempotent replay, lost race), account naming
  convention, balance arithmetic property (sum of postings == balance delta).
- **ITs (PG16 + LocalStack TC; `runOnce()`-driven; static test creds — the E6 S6 lesson; zero sleeps):**
  - **IT1 — M2 full loop:** create → simulator pay → webhook → relay → ledger queue → consumer → journal
    entry + 3 postings + balances (available +9900, fees +100, processing −10000 debited) → proof ok.
  - **IT2 — idempotent redelivery:** same confirmed message twice → one journal row; second processed as
    duplicate ack.
  - **IT3 — non-posting events:** created, failed, unknown type → events row IGNORED, zero postings, proof ok.
  - **IT4 — proof & rebuild:** post N events, proof ok; test-local `UPDATE ledger.balances` corrupts one
    account → proof `ok:false` with the diverging account; `POST /v1/ledger/rebuild` → proof ok again.
  - **IT5 — settlement:** settle merchant → 201, postings balanced (available → 0, payouts debited),
    balances updated; replay same key → same settlement (no double); settle again with zero balance → 409.
  - **IT6 — poison to DLQ:** structurally unparsable body → not acked → after redrive config the message is
    received from `dargent-payments-ledger-dlq.fifo`; app healthy; proof untouched.
- **Hygiene gates (S7):** no `org.springframework.*` in `modules/ledger` main (except nothing — it's a
  module: zero Spring imports there); `software.amazon.awssdk` in ledger confined to
  `adapter/out/messaging/`; no cross-module import `io.dargent.payments` inside ledger sources; no
  `Thread.sleep`; greps pasted with their commit id.

## 8. Risks & troubleshooting

| Risk | Mitigation |
|---|---|
| Signed-balance confusion (credit-positive vs debit-positive) | §5.2 convention fixed; property tests encode it; proof catches any drift |
| Consumer silently swallowing poison | §5.3 ack policy is binary and IT-proven (IT6) |
| Settlement double-fire under retry | idempotency_key UNIQUE + replay response (IT5) |
| Money math drift between events and postings | `fee + net == amount` gate → REJECTED + alarm, never partial |
| Fan-out assumption vs LocalStack reality | ITs assert SNS→both queues delivery as the contract, not LocalStack internals |
| Ledger growing unpurged | Accepted + documented (§5.8); E14 archival |

## 9. Closure checklist (epic DoD)

- [ ] Step 0: TD-13 residual fixed (register row's list, docs-only, green run cited)
- [ ] IT1–IT6 green in CI, test name + run pair each (API-verified)
- [ ] Proof + rebuild live; settlement with idempotent replay; poison path lands in DLQ
- [ ] BoE addendum landed; journal-never-purged documented
- [ ] Boundary greps (ledger⇄payments independence; AWS confinement) pasted with commit id
- [ ] `.env.example` §4.1 rows; init script idempotent (re-run proof); scope diff = 0 (payments prod untouched)
- [ ] Matrix zero pending; **flip = last content commit + exactly one post-flip citation commit recording the
      flip's run pair, that commit green** (E6's consecrated rule); M2 stays ◐ until E10 (README honest)
