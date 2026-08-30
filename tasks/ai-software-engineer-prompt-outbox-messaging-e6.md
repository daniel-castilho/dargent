# AI Software Engineer Prompt — Epic E6: Outbox & Messaging Backbone

**Issued:** 2026-08-30 · **Executor:** the AI Software Engineer · **Milestone:** M2
**Mission:** make the money events leave the database — relay `payments.outbox` to SNS/SQS FIFO with DLQ,
retention, and derived sizing — and first fix the documentation truth you will be building on (TD-13).

---

## Where you are starting from (verified facts — cite pairs, never memory)

- main = `f530b18` (docs-only governance push by the owner; parent `3b60ba8`). Last code-evidencing run:
  #30 (`33333739409`) on `3b60ba8`, green. E3/E4/E3R flipped ✅ (flips audited TRUE).
- **Open defect TD-13 (spec §2):** the R7 citation layer (e3r matrix, ledger flip rows, correction note)
  carries register misquotes (TD-9's off-by-one echo), the rejected-paraphrase R-structure, missing
  BD-10…BD-14/TD-7…TD-11 traceability rows, and systematic run number↔id mispairings. Your FIRST commit
  fixes it. The verified pair table is in `tasks/e3r-block1-verification.md`.
- `V105__outbox.sql` already carries the delivery state machine (`PENDING/SENT/FAILED/EXHAUSTED`,
  `attempt_count`, `next_attempt_at`, `published_at`, partial poll index) — **E6 writes zero migrations.**
  Verify this file at S0 before writing code; any drift from spec §2 = stop-and-report.
- Writers land `PENDING` rows (create: `payment.created`/`payment.failed`; webhook: `payment.confirmed`).
  Nothing polls today; no AWS SDK in the tree; no LocalStack in compose.

## Sources of truth — binding, in this order

1. `tasks/outbox-messaging-e6-spec.md` — §4.1 env contract, §5 exact contracts, §5.7 BoE (defaults are
   DERIVED — do not tune by taste), §7 tests, §9 DoD.
2. `tasks/outbox-messaging-e6-implementation-sequence.md` — S0…S7 order, commit messages, global rules,
   failure playbooks.
3. `tasks/outbox-messaging-e6-backlog.md` — acceptance anchors per item.
4. `tasks/create-webhook-remediation-e3r-spec.md` §2 + `tasks/e3r-block1-verification.md` — the governance
   rules you inherited (message=diff; stop-and-report; pair-citing) and the canonical run table.

## Step 0 — TD-13 correction (docs-only, before ANY E6 code)

One commit, exact list in backlog S0: fix the e3r matrix's register ids and R-structure from the committed
e3r spec; add the BD-10…BD-14 + TD-7…TD-11 rows; reconcile every run pair against the canonical table;
update the artifact index (e3 row + new E6 row); paste the hygiene greps with the commit id they ran at.
**No code, no re-flip, no history rewrite.** Commit:
`docs(e3r): TD-13 citation-layer correction — matrix register ids, run-id pairs, artifact index`.

## Build order (one commit per step; every push green; pairs cited)

1. **S1 `feat(payments): UUIDv7 outbox row ids (OutboxId VO)`** — pure VO + property tests (§5.5); switch the
   three writers; V105's comment stops being a lie. eventId stays v4 — deferred, do not touch.
2. **S2 `feat(payments): outbox delivery use case with claim/backoff/mark policy`** — pure TDD (§5.1):
   claim (due filter, batch), strict-Jackson eventId extraction (lesson #13 — hand-rolled parsing is a
   registered defect class), publish→mark (conditional), backoff 30 s/2 m/5 m (§5.2), parse-defect path.
3. **S3 `feat(api): SNS event publisher + LocalStack compose topology`** — `SnsEventPublisher` (AWS SDK v2,
   imports confined to `adapter/out/messaging/`), compose LocalStack, idempotent `deploy/localstack-init.sh`
   (topic + notify queue + DLQ + redrive 5 + subscription), `.env.example` gains every §4.1 row.
4. **S4 `test(payments): relay ITs — publish, retry, SKIP LOCKED race`** — IT1–IT3 (§7), PG16 + LocalStack
   Testcontainers, `runOnce()`-driven, zero sleeps.
5. **S5 `feat(payments): outbox retention purge + BoE sizing doc`** — purge batches (§5.4) + IT4 +
   `docs/load-test-baseline.md` with the §5.7 table (assumptions labeled as such).
6. **S6 `test(payments): purge, end-to-end delivery, topology ITs`** — IT4–IT6; **IT5 is the M2 anchor**:
   create → simulator pay → webhook confirm → `runOnce()` → `payment.confirmed` ON THE QUEUE.
7. **S7 `docs(e6): matrix, README/CHANGELOG truth-scoped, ledger flip` (LAST commit)** — `e6-acceptance-matrix.md`
   cell-by-cell (defect-free numbering, pairs from YOUR verified table), README/CHANGELOG claim only what
   cited runs prove, ledger E6 ✅ with the final run id at that HEAD.

Block commissioning: **Block 1 = S0–S3**, **Block 2 = S4–S7**. Handoff after each block; both are audited
via API like Blocks 1–3 of E3R (messages vs diffs, sources read line-level, pairs re-verified).

## Non-negotiable rules

1. **At-least-once is the guarantee** — §5.6 statement in javadoc and matrix; nobody writes "exactly once".
2. Zero migrations; V105 drift → stop-and-report. Zero new env names beyond §4.1. Zero new deps beyond the
   two AWS SDK modules + localstack testcontainer (anything else = stop-and-report).
3. Scope discipline: no `modules/ledger`, `modules/notifications`, `apps/psp-simulator` — `git diff --stat`
   checked before every push.
4. Commit message = diff (pre-push hunk check); pairs (number AND id) re-verified against the canonical
   table before every handoff — TD-13 exists because labels drifted three handoffs in a row.
5. EXHAUSTED/requeue/republish belong to E9; consumers to E10; do not absorb them.

## Stop conditions

| When | Do |
|---|---|
| V105 ≠ spec §2 in any column/index | Stop-and-report (schema↔spec rule) |
| A needed dependency/env is not in the authorized lists | Stop-and-report |
| LocalStack flakiness >1 per run | Compose healthcheck fix, disclosed — never sleeps/masks |
| A matrix cell lacks a real test + pair | Cell stays open and says so |
| CI red on any push | Artifact, classify in writing, fix |

## Handoff report (will be API-audited)

- S0 diff summary; then per step: commit sha, message, test names, run number+id pairs (canonical-table
  checked), IT evidence for IT1–IT6 (IT5 screenshot-equivalent: received message body + attrs quoted).
- Purge proof (IT4), BoE doc path, `.env.example` diff, hygiene grep outputs **with the commit id they ran at**.
- Final: matrix path, ledger row text as committed, final run pair at the flip HEAD.

Then stop. On verified evidence E6 closes, **E5 (expiration/reconciliation) is commissioned** next; E3.5 and
the V107 typo follow.
