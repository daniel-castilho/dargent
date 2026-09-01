# Execution Prompt — E10 Block 1: Riders + Notifications Module Backend

**Issued:** 2026-09-01 · **Executor:** the AI Software Engineer · **Auditor:** the governance side
(API-audited: messages vs diffs, run pairs by number AND id, greps at the cited commit).
**Nature:** new epic's first block. E10 was commissioned by the owner with the riders included.
**Push is the owner's action.**

## Where you are starting from (verified facts — cite pairs, never memory)

- main = `de54825910de92844bfeae020d40f33b8ba016b2` ("docs(e7): record BD-16 run pair (#74)"),
  run **#75 `33560118008`** green. BD-15/BD-16 CLOSED in the register; residuals: **TD-15**,
  **BD-15R**, e7 matrix race-row dangling citation pair — all three ride this block as step 0.
- The notify queue exists (E6 topology: SNS topic fan-out → notify FIFO + notify DLQ) and has
  **no consumer** — E10 is that consumer and closes M2.
- `modules/ledger` is the reference implementation: consumer semantics, reader shape (as of
  `e946a15`), store port pattern, IT harness, hygiene gates.
- `modules/notifications` may be hollow or partially scaffolded — LIST ITS CONTENTS FIRST.

## Sources of truth — binding

1. `tasks/notifications-e10-spec.md` — §1–§6 and §8–§9 govern this block (§7 is Block 2).
2. `tasks/notifications-e10-sequence.md` — order + failure playbooks (P1–P6).
3. `tasks/notifications-e10-backlog.md` — S0–S5 acceptance.
4. `docs/epics.md` (E10 row, M2), `AGENTS.md` §2/§3.3/§3.4/§9d, `docs/coding-standards.md` §10.

## Step 0 — Riders (three commits, in order, each green before the next)

### 0a. `docs: README honesty pass (TD-15)` — README.md only
1. Recast the reconciliation sentence: the drop-once proof (E2) is stated as real; recovery via
   reconciler is stated as FUTURE (E5, not started). Delete the false present-tense claim.
2. Scope the subtitle as an architecture analogy (one clause; no product-equivalence reading).
3. Mark the money-flow block as target state where it narrates refunds/reconciler/resurrection/
   notifications not yet real (keep honest anchors like M3 "Suffering" and the "runs today — by
   design" line intact).
4. Fix the stale "will show PENDING until E4 webhook arrives" comment (E4 is complete; the wait
   is the PSP's webhook).
5. Cross-check every remaining README claim against the Current-state table; fix any other
   present-tense future you find (if the edit grows beyond README honesty, STOP — P5).

### 0b. `test(ledger): failure-injection leg in BD-15 guard IT (BD-15R)` — LedgerMoneyLoopIT.java only
Extend `redelivery_after_posting_failure_resumes_and_posts_exactly_once` with the adjudicated
first leg, keeping the existing redeliver leg as the second leg:
1. Inline DDL in the test method (test infrastructure, never Flyway): `CREATE OR REPLACE FUNCTION
   fail_journal_insert() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'simulated journal failure';
   END; $$ LANGUAGE plpgsql;` + `DROP TRIGGER IF EXISTS trg_fail_journal_insert ON
   ledger.journal_entries;` + `CREATE TRIGGER trg_fail_journal_insert BEFORE INSERT ON
   ledger.journal_entries FOR EACH ROW EXECUTE FUNCTION fail_journal_insert();`
2. Deliver a valid `payment.confirmed` via `ingestion.processMessage(raw)`: assert **false**
   (nack), row stays **RECEIVED**, **zero** journal rows, zero postings.
3. Drop the trigger mid-test (`DROP TRIGGER IF EXISTS trg_fail_journal_insert ON
   ledger.journal_entries;`) — the redeliver leg then asserts the existing exactly-once resume
   (ack true, 1 journal, 3 postings, exact balances, proof ok, status POSTED).
4. `@AfterEach`: `DROP TRIGGER IF EXISTS trg_fail_journal_insert ON ledger.journal_entries;`
   (container reuse safety net — adjudicated Q2).
5. Do NOT touch the production `EventIngestionUseCase` (its first-delivery nack + RECEIVED-left
   behavior is exactly what this leg now proves end-to-end). If the leg exposes a REAL defect,
   STOP — that is a register event, not a test edit.

### 0c. `docs(e7): complete race-row citation pair in matrix` — tasks/e7-acceptance-matrix.md only
The corrected race row cites `#59 33462467004 /` with an empty pair after the slash. Complete it
with the BD-15 guard evidence (`#72 33555099220 3ae463e`). One line. Nothing else.

## Steps 1–5 — Module backend (spec §1–§6, §8, §9)

Order and acceptance per `tasks/notifications-e10-backlog.md` (S1–S5); contracts per spec.
Commit sequence suggestion (adjust only with reasons in the handoff):
- `feat(notifications): module scaffold and notifications schema (E10 S1)` — pom (disclose any
  new dependency in the message), Flyway migration, module builds.
- `feat(notifications): Jackson 3 envelope reader (E10 S2)` — reader + unit matrix.
- `feat(notifications): notification ingestion use case and store (E10 S3)` — port + JDBC +
  use case + fake-based unit matrix.
- `feat(notifications): sqs notification consumer and wiring (E10 S4)` — consumer + app config +
  env §4.1 + consumer unit contract test.
- `test(notifications): loop and poison-dlq integration tests (E10 S5)` — the two ITs, green in CI.

## Non-negotiables & stop conditions

- Env names exactly §4.1; defaults exactly §4.1. Any other new name = STOP.
- Zero prod touches in `modules/payments`, `modules/ledger` (0b is ledger TEST only), zero in
  `apps/psp-simulator`.
- Jackson 3 only; AWS SDK only in `adapter/out/messaging/`; zero Spring annotations in module
  main; per-module Flyway; no JPA; binary ack; idempotency by UNIQUE constraint.
- Greps §9 pasted with the commit id they ran at.
- STOP-and-report on: any red you cannot explain in writing; any test that needs a weakened
  assert; any felt need for a schema change beyond §2.1; any divergence from the mirror shape
  you cannot justify in one sentence (and then only with prior approval — P2).
- Never reference an artifact before it exists; commit message = diff (pre-push hunk check).

## Handoff report (API-audited)

- Rider commits: shas + messages + which register item each closes.
- Module commits: shas + messages; test names + run pairs (number AND id); grep outputs with
  commit ids; the IT bodies' mechanics quoted from source (queue attrs, redrive, assert flow).
- Anything NOT done, with reason; every clarification asked BEFORE the divergence.
- Exact head sha at handoff time.

Then stop. On verified evidence: register riders zero out, Block 2 (read API + docs + flip +
M2 closure) is commissioned.
