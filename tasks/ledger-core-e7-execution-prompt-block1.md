# Execution Prompt — E7 Block 1: Journal Foundations (S0 → S3)

**Issued:** 2026-08-31 · **Executor:** the AI Software Engineer closing epic E7
**Valid for:** exactly S0 (TD-13 residual) + S1 (schema) + S2 (ingestion use case) + S3 (SQS consumer +
topology). **No settlement, no HTTP surface, no ITs beyond units, no README/CHANGELOG, no flips** — Block 2
owns S4–S7. One commit per step; **push is the owner's**; every push green.
**Operating principle (unchanged):** a green CI proves that tests pass — not that they are right, and not
that the code exists. Evidence = CI test cited by name + run number AND id.

---

## Where you are starting from (verified facts — re-verify from YOUR tree)

- main = `e6d8751`; runs #47 (`33355346290`, flip) and #48 (`33355665328`) green. E6 closed; the
  `payment.confirmed` event reaches `dargent-payments-notify.fifo` with `MessageGroupId=txid`,
  `MessageDeduplicationId=eventId`, payload `{amount, fee, net, late}` in the E3 §5.2 envelope.
- **TD-13 residual** (e3r spec row) — your first commit, exactly the registered list.
- `modules/ledger`: empty E0 skeleton; **JDBC (`JdbcClient`) only — no JPA, no entities, no Spring imports
  in the module's main sources**. List `modules/ledger/src/main/resources/db/migration/` via the contents
  API before writing anything (audited 404 pattern: absence is only proven by listing).
- Stack & deps: authorized new dependency = `software.amazon.awssdk:sqs` (ledger only). SQS/SNS
  testcontainers deps already exist from E6. NOTHING else — additions = stop-and-report.
- Compose/init-script healthcheck for LocalStack already strict (`sns`+`sqs` running) from E6.

## Sources of truth — binding, in this order

1. `tasks/ledger-core-e7-spec.md` — §2 current state, §4.1 env, §5 contracts (§5.3 posting rules = the
   money math), §5.7 guarantee statement.
2. `tasks/ledger-core-e7-implementation-sequence.md` — rules + playbooks.
3. `tasks/ledger-core-e7-backlog.md` — anchors.
4. Canonical pair tables: `tasks/e3r-block1-verification.md` + E6 matrix §Run IDs.

## S0 — TD-13 residual (docs-only, one commit, before any code)

Exactly the registered list (e3r spec, TD-13 row "Fix (residual)"): fix the off-by-one rows (requestId=BD-5,
snapshot=BD-6, random actor=BD-7, String.format=BD-8, callback=BD-9, read side=BD-10); restore the real
R-structure (R3 = read side corrections, single R6 section, R7 = docs truth, R8 = governance); fix R0's
"#15/#17 = `33288538459`" → **#20**; correct the TD-10 row (stale-grep defect, #22 = `33289414922`); add
TD-12 and TD-13 rows citing their commit ids (`3b60ba8` / TD-13's own fix commit). No code, no flips.
Commit: `docs(e3r): TD-13 residual — register ids, R-structure, R0/TD-10 pairs, TD-12/13 rows`.

## S1 — `feat(ledger): journal, postings, balances, settlements schema`

- `modules/ledger/src/main/resources/db/migration/ledger/V101__journal.sql`, `V102__balances.sql`,
  `V103__settlements.sql` — DDL per spec §5.2: CHECKs exact (`direction IN ('DEBIT','CREDIT')`,
  `amount_cents > 0`, event `status IN ('POSTED','IGNORED','REJECTED')`), `event_id` PK on `ledger.events`
  (the dedupe target), `journal_entries.event_id UNIQUE`, `settlements.idempotency_key UNIQUE`.
- UUIDs default v4 via app-supplied values (`OutboxId`-style VOs are payments-internal; ledger ids are
  plain uuids generated in the use case — do NOT import anything from payments).
- Flyway per-module config already exists (E0); verify it points at the ledger location; a config gap here
  is a disclosed wiring fix, not a silent workaround.

## S2 — `feat(ledger): event ingestion use case with dedupe, posting rules, strict reader`

Pure TDD, fakes, zero Spring. Contracts (spec §5.3/§5.4/§5.7, binding):

- `EventEnvelopeReader` (ledger-local): strict Jackson 3 over the wire body; missing/blank `eventId` or
  unparsable JSON → **poison signal** (consumer will not ack); known types parsed with `amount`, `fee`,
  `net`, `late`, `txid`, `merchantId` read defensively (explicit failures, no nulls leaking).
- Posting rules exactly §5.3 — `payment.confirmed` → 3 postings (DEBIT `payments:processing` amount;
  CREDIT `fees:revenue` fee; CREDIT `merchant:{merchantId}:available` net) with the `fee + net == amount`
  gate → REJECTED + ERROR when violated; created/failed/unknown → IGNORED with the standard notes.
- Ingestion tx: **first statement** dedupes (`INSERT … ON CONFLICT (event_id) DO NOTHING`); duplicate →
  ack-skip outcome; then journal + postings + `ledger.balances` upsert (credit-positive signed math) in the
  SAME tx; expose `runOnce(messages)` semantics for deterministic tests.
- `LedgerStore` port in `domain/port/out/`; the JDBC adapter comes in this step or S3 (your call — one
  adapter class, `JdbcClient`, records; disclose the split).
- Javadoc carries §5.7 verbatim (at-least-once + local dedupe; exactly-once-per-event by construction).

## S3 — `feat(ledger): SQS consumer + fan-out queue topology`

- `deploy/localstack-init.sh` v2: add `dargent-payments-ledger.fifo` + `dargent-payments-ledger-dlq.fifo`
  (`FifoQueue=true`), subscription topic→ledger queue, RedrivePolicy `maxReceiveCount=5` — **idempotent**,
  and you prove it by running the script twice and showing the second pass no-ops.
- `SqsEventConsumer` (`adapter/out/messaging/`; SQS imports confined there): receive (batch ≤ 10,
  `DARGENT_LEDGER_POLL_MS` between empty receives), delegate to the use case, **ack only committed work;
  never ack poison** (§5.3 binary policy — IT6 in Block 2 will prove the DLQ path).
- Host in `apps/api` behind `DARGENT_LEDGER_CONSUMER_ENABLED` (default false), `ThreadPoolTaskScheduler` +
  `SmartLifecycle` + `runOnce()` — same shape as the E6 relay host. No Reactor, no new scheduler stack.
- `.env.example` gains the §4.1 rows verbatim. Compose unchanged otherwise (LocalStack already there).

## Non-negotiable rules

1. S0 docs; S1–S3 the only code. Anything else discovered = stop, report, register.
2. Zero payments prod changes; zero payments-migration edits; boundaries: `modules/ledger` imports nothing
   from `io.dargent.payments.*` — grep proves it at handoff.
3. Commit message = diff (pre-push hunk check); pairs cited number AND id from the canonical tables.
4. Scope: `modules/ledger`, `apps/api`, `deploy/localstack-init.sh`, compose, `.env.example`, `tasks/` —
   `git diff --stat` before every push.
5. 100% English; injected `Clock`; zero `Thread.sleep`; zero `Instant.now()` in prod request paths.

## Stop conditions

| When | Do |
|---|---|
| Migration dir/config differs from §2 assumptions | Stop-and-report (after listing) |
| Posting math or account names ambiguous in a case §5.3 doesn't cover | Stop-and-report — money math is owner territory |
| Dep/env outside the authorized lists | Stop-and-report |
| CI red on any push | Artifact, classify in writing, fix |

## Handoff report (will be API-audited like E3R/E6)

- S0: matrix rows before/after (ids + pairs); greps with commit id.
- S1: migration file list from the tree; Flyway config evidence; applied-on-PG16 proof (unit/context test).
- S2: unit test names + run pair; the reader's poison/ignore/reject branch map.
- S3: init-script double-run proof; consumer wiring behind the flag; `.env.example` diff.
- Anything NOT done from this prompt, with reason; clarifications asked BEFORE diverging.

---

## Clarifications — adjudicated before execution (2026-08-31; owner veto window closes at S1's commit)

1. **Q1 — `LedgerMigrationIT` and the Flyway PG extension:** YES — rewrite the test to use
   `flyway-database-postgresql` properly and **delete the reflection workaround entirely** (reflection to
   patch version detection is hidden magic; it must not survive into any committed tree). The dependency is
   **authorized**: verified at `e6d8751`, `apps/api/pom.xml` already ships `flyway-database-postgresql`
   (runtime) and `modules/payments/pom.xml` carries `flyway-core` + the extension **test-scoped for the
   same purpose** (module ITs on PG16) — mirror that pattern verbatim in `modules/ledger/pom.xml` (test
   scope). Condition: the S1 commit message **discloses the pom addition** ("flyway test deps mirroring
   modules/payments") so message = diff holds. If, with the proper extension, the PG16 version-detection
   issue persists → stop-and-report (a Flyway/Boot BOM pin is an owner decision, never reflection).
2. **Q2 — S2 unit tests:** written WITH S2, tests-first (red → green), never deferred. S2 is "done" only
   with its unit suite green (that is its acceptance anchor); moving to S3 with S2 unproven violates the
   sequence. One commit per step, as planned.
3. **Q3 — consumer host:** CONFIRMED — `LedgerCompositionConfig` in `apps/api` (composition root, beside
   `PaymentsCompositionConfig`) mirrors the relay pattern exactly: `ThreadPoolTaskScheduler` +
   `SmartLifecycle` + `runOnce()` behind `DARGENT_LEDGER_CONSUMER_ENABLED` (default `false`). No Reactor,
   no new scheduling stack.
4. **Priority list endorsed** (S1 fix → module verify → S2 tests+impl → S3 → commits): with the conditions
   that commits stay one-per-step with the sequence file's conventional messages, and **push remains the
   owner's action** — per-commit push preferred (each step gets its own CI run pair for the matrix);
   if the owner batches the Block 1 push, commits stay atomic and one green run covers the batch, cited
   for all four steps.

Then stop. **Block 2 (S4–S7: settlement/rebuild/HTTP, ITs 1–6, BoE addendum, matrix + flip with the
post-flip citation commit) is commissioned on verified evidence of this handoff** — audited via API,
line-level, never from the report.
