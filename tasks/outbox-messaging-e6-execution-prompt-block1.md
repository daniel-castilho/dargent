# Execution Prompt — E6 Block 1: Relay Foundations (S0 → S3)

**Issued:** 2026-08-30 · **Executor:** the AI Software Engineer closing epic E6
**Valid for:** exactly S0 (TD-13 correction) + S1 (OutboxId UUIDv7) + S2 (delivery use case) + S3 (SNS
publisher + LocalStack topology). **No acceptance matrix, no README/CHANGELOG edits, no ledger flips in this
block** — those are Block 2 (S4–S7). One commit per step; **push is the owner's action**; every push green.
**Operating principle (unchanged):** a green CI proves that tests pass — not that they are right, and not
that the code exists. Evidence is a test that runs in CI, cited by test name + run number AND id.

---

## Where you are starting from (verified facts — cite pairs, never memory)

- main = `f530b18` (owner's docs push; parent `3b60ba8`). Last code-evidencing run: #30 (`33333739409`) on
  `3b60ba8`, green. E3/E4/E3R flipped ✅; flips audited TRUE.
- **TD-13 is open** (e3r spec §2): the R7 citation layer carries register misquotes (TD-9's off-by-one
  echo), the rejected-paraphrase R-structure, missing BD-10…BD-14/TD-7…TD-11 traceability rows, and
  systematic run number↔id mispairings. The verified pair table lives in
  `tasks/e3r-block1-verification.md`. Your FIRST commit fixes this — E6 never builds on lying docs.
- `V105__outbox.sql` already carries the delivery state machine (`status CHECK IN
  ('PENDING','SENT','FAILED','EXHAUSTED')`, `attempt_count`, `next_attempt_at`, `published_at`, partial
  index `idx_outbox_pending_due … WHERE status='PENDING'`). **E6 writes zero migrations.** Re-read this file
  from YOUR working tree at S0; any drift vs spec §2 = stop-and-report (schema↔spec rule, BD-14's law).
- Writers land `PENDING` rows: create (`payment.created`/`payment.failed`), webhook (`payment.confirmed`) —
  via the `OutboxWriter` port + JDBC adapter, envelope serialized Jackson-3 (E3 §5.2). Nothing polls today;
  no `software.amazon.awssdk` import exists; compose has no LocalStack.

## Sources of truth — binding, in this order

1. `tasks/outbox-messaging-e6-spec.md` — §2 current state, §4.1 env contract, §5 exact contracts, §5.7 BoE.
2. `tasks/outbox-messaging-e6-implementation-sequence.md` — S0…S7, global rules, failure playbooks.
3. `tasks/outbox-messaging-e6-backlog.md` — acceptance anchors.
4. `tasks/create-webhook-remediation-e3r-spec.md` §2 + `tasks/e3r-block1-verification.md` — inherited
   governance + the canonical run pair table (needed by S0).

## S0 — TD-13 citation-layer correction (docs-only, one commit, BEFORE any code)

Exactly this list (spec register row TD-13 "Fix" column is the contract):

1. `tasks/e3r-acceptance-matrix.md`: rewrite the register-traceability table from the **committed e3r spec
   §2** — correct register ids (requestId=BD-5, snapshot=BD-6, random actor=BD-7, String.format=BD-8,
   callback=BD-9, read side=BD-10), restore the real R-numbering (R7=docs truth, R8=governance; kill the
   "R3 idempotency PK" and duplicate-R6 paraphrase structure), and ADD the missing rows BD-10, BD-11,
   BD-12, BD-13 (+ residual), BD-14, TD-7…TD-11 — each with fix commit, test name, run pair.
2. Reconcile EVERY run number↔id pair in the matrix against the canonical table, including the flip
   citations: BD-13 residual = **#28 `33329581906`** (`7abee75`); BD-11 failure-injection = **#29
   `33331033505`** (`1e9dec6`); docs R7/R8 = **#30 `33333739409`** (`3b60ba8`); `33288538459` = **#20**
   (A0 golden, `c2809c1`) wherever it appears as #15/#17/#22.
3. `docs/epics.md`: fix the flip rows + correction note to carry correct pairs and cite the final **#30**;
   artifact index — E3 row no longer "not yet committed", E6 row added (`tasks/outbox-messaging-e6-*`).
4. Hygiene greps re-run at YOUR docs commit and outputs pasted in the handoff with that commit id.
5. **No code, no re-flip, no history rewrite.** Commit:
   `docs(e3r): TD-13 citation-layer correction — matrix register ids, run-id pairs, artifact index`

## S1 — `feat(payments): UUIDv7 outbox row ids (OutboxId VO)`

- New pure VO `OutboxId` in `modules/payments` domain: UUIDv7 per RFC 9562 (48-bit ms epoch from the
  injected `Clock`, version nibble 7, variant 10). Property tests: version + variant bits, monotonic
  non-decreasing under a fixed clock, uniqueness across 10k generations.
- Switch all three outbox writers to it. V105's `-- UUIDv7` comment stops being a lie.
- **The envelope `eventId` stays v4** — time-ordered eventId is a documented deferred option (ideas ledger
  §6); touching it = defect.
- Unit tests only (no Spring); run pair cited.

## S2 — `feat(payments): outbox delivery use case with claim/backoff/mark policy`

Pure TDD with fakes — no Spring context in these tests. Contracts (spec §5.1/§5.2, binding):

- `runOnce()` = one worker, one pass: **claim** (`SELECT id, aggregate_id, type, payload, request_id,
  attempt_count FROM payments.outbox WHERE status='PENDING' AND next_attempt_at <= now() ORDER BY
  next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED` — partial index does the work) → **parse** eventId
  from `payload` via the injected Jackson 3 `ObjectMapper`, strict (`readTree`; missing/blank eventId = row
  LEFT + ERROR log — writer bug, never dropped) → **publish** via the `EventPublisher` port (body = stored
  `payload::text` verbatim, never re-serialized) → **mark** (`UPDATE … SET status='SENT',
  attempt_count=attempt_count+1, published_at=now() WHERE id=:id AND status='PENDING'`; 0 rows = lost race,
  log + move on).
- Publish error → `attempt_count+1`, `next_attempt_at = now()+backoff` (1→30 s, 2→2 min, ≥3→5 min cap), row
  STAYS `PENDING` (E6 has no terminal state; `EXHAUSTED` is E9's).
- Purge policy as a separate pure collaborator: every 60th cycle, batched `DELETE` of `SENT` rows older than
  `DARGENT_OUTBOX_RETENTION_DAYS` (LIMIT 1000); `PENDING`/`FAILED` never touched. (Its IT is S5; ship the
  unit-tested logic here or in S5 — your call, one commit each way is fine, disclose it.)
- Javadoc carries the §5.6 guarantee statement: **at-least-once, per-payment FIFO ordering, dedup by
  `MessageDeduplicationId=eventId`, consumer idempotency = E10's contract. Nobody writes "exactly once".**

## S3 — `feat(api): SNS event publisher + LocalStack compose topology`

- `SnsEventPublisher` (`modules/payments/adapter/out/messaging/`): AWS SDK v2 (`software.amazon.awssdk:sns`
  + url-connection client), `MessageGroupId = aggregate_id`, `MessageDeduplicationId = eventId`,
  `Subject = type`, per-call timeout `DARGENT_EVENTS_PUBLISH_TIMEOUT_MS`. **AWS imports confined to this
  package** — grep proves it at handoff.
- `apps/api` wiring: relay loop host gated by `DARGENT_RELAY_ENABLED` (default `false` — CI stays unaffected
  until Block 2's IT profile flips it in tests).
- Compose: LocalStack service + **`deploy/localstack-init.sh`, idempotent** (re-run no-ops): topic
  `dargent-payments-events.fifo`, queue `dargent-payments-notify.fifo` + DLQ
  `dargent-payments-notify-dlq.fifo` (both `FifoQueue=true`), subscription topic→queue, RedrivePolicy
  `maxReceiveCount=5`.
- `.env.example` gains EVERY row of spec §4.1 verbatim (`DARGENT_RELAY_ENABLED/_WORKERS/_POLL_MS/_BATCH`,
  `DARGENT_OUTBOX_RETENTION_DAYS`, `DARGENT_EVENTS_PUBLISH_TIMEOUT_MS`, `DARGENT_EVENTS_TOPIC_ARN`,
  `DARGENT_EVENTS_QUEUE_URL`, `AWS_REGION`, `AWS_ENDPOINT_URL`). New env names beyond §4.1 =
  stop-and-report. Defaults cite §5.7 (derived, not tuned).

## Non-negotiable rules

1. **S0 is docs; S1–S3 are the only code in this block.** Anything else discovered = stop, report, register.
2. Zero migrations; V105 drift → stop-and-report. Zero new deps beyond the two AWS SDK modules (+ localstack
   testcontainer is Block 2's, when the ITs need it — adding it early is a disclosed deviation, not a silent one).
3. Commit message = diff: pre-push self-check, every bullet matched to a hunk (the TD-11 discipline).
4. Every handoff cites run number AND id, re-verified against the canonical table (the TD-13 discipline).
5. Scope: `modules/payments`, `apps/api`, `deploy/` (init script), compose, `.env.example`, `tasks/`. Zero
   lines in `modules/ledger`, `modules/notifications`, `apps/psp-simulator` — `git diff --stat` checked
   before every push.
6. 100% English identifiers/comments/logs; injected `Clock`/sleeper; zero `Thread.sleep`; zero
   `String.format` JSON.

## Stop conditions

| When | Do |
|---|---|
| V105 ≠ spec §2 (column, index, check) | Stop-and-report — owner decision, never improvise |
| A needed dep/env is outside the authorized lists | Stop-and-report |
| CI red on any push | Artifact, classify in writing, fix — never push on an unexplained red |
| S0 exposes a docs divergence the list doesn't cover | Register it, fix in the same docs commit only if it's a label/pair; content decisions go to the owner |

## Handoff report (will be API-audited like E3R Blocks 1–3)

- S0: the matrix diff summary; before/after of the register table (ids + pairs); greps with commit id.
- S1/S2/S3: commit sha + message + test names + run pair each; the V105 verification quote from your tree;
  the guarantee statement's javadoc location; the idempotency proof of the init script (re-run output).
- Explicit list of anything you did NOT do that the prompt lists, with reason.

Then stop. **Block 2 (S4–S7: ITs 1–6 incl. the M2 anchor E2E, BoE doc, closure + flip) is commissioned on
verified evidence of this handoff** — audited via API: messages vs diffs, sources read line-level, pairs
re-verified against the run object, never the report.

---

## Clarifications — adjudicated before execution (2026-08-30; auditor rulings within spec bounds, owner veto window closes at S1's commit)

1. **Clock (Q1):** reuse the existing composition-root `Clock` bean. No dedicated bean, no second time
   source. Tests inject their own fixed `Clock` via constructor, as everywhere else in the system.
2. **Relay persistence (Q2):** YES to `OutboxEventStore` in `domain/port/out/` beside `OutboxWriter` — with
   ONE hard constraint: it is implemented by the **same JDBC adapter class** that serves `OutboxWriter`
   (one adapter, one table-access path; no parallel store). The delivery use case depends only on the new
   port; the writers keep `OutboxWriter`. No renaming, no ripples.
3. **EventPublisher port (Q3):** `domain/port/out/`, same package as `OutboxWriter`. Module-internal
   consistency wins over layering purism; implemented by `SnsEventPublisher` in `adapter/out/messaging/`.
4. **Loop host (Q4):** Spring's `ThreadPoolTaskScheduler` (already on the classpath) hosting a
   `SmartLifecycle` bean in `apps/api`, gated by `DARGENT_RELAY_ENABLED` (default false), N workers = N
   scheduled `runOnce()` + poll-sleeper instances. **Reactor is rejected** — it is a new dependency for a
   poll loop, outside the authorized list (zero-new-deps rule).
5. **LocalStack testcontainer (Q5):** Block 2, as the prompt already states — test-scoped and unused in S3
   is dead weight Block 1 doesn't carry. If added in S3 anyway, it is a **disclosed deviation** in the
   commit body, not a silent one.
6. **Init script location (Q6):** `deploy/localstack-init.sh` confirmed (spec stands). Compose bind-mounts
   it by relative path (e.g. `../deploy/localstack-init.sh` from the compose dir). E12's blue-green assets
   will share `deploy/` later.
7. **Healthcheck (Q7):** `/_localstack/health`, **strict variant**: `curl -sf` AND require both `"sns"` and
   `"sqs"` reported running in the payload (the ITs depend on both), `start_period` ≈ 30 s, sane
   interval/retries. CI flake playbook unchanged: one retry, then red = artifact + written classification.
