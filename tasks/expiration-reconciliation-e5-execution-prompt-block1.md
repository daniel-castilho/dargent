# Execution Prompt — E5 Block 1: DEBT-1 rider + Expiration + Reconciler core

**Issued:** 2026-09-02 · **Executor:** the AI Software Engineer · **Auditor:** the governance side
(API-audited: messages vs diffs, run pairs number AND id, reds always cited). **Push is the owner's
action.**

## Where you are starting from (verified facts — cite pairs, never memory)

- main = `4f3e8f2c1f` (E10 TD-20 rider citation), runs #123 `79aa8e5abb` GREEN / #124 `33690751677`
  (citation run — unregistered per precedent). **E10 ✅ / M2 ✅ are closed.** E5 unblocked since E3R;
  this is the M3 opener.
- Payments migrations @main: V101–V106, V108, V110 (V107 gap is history — do NOT reuse the number;
  **V111 is the E5 slot (owner decision 2026-09-02)** — V109 does not exist, V110 is taken; if V111 is
  taken when you start, take the next free and disclose).
- `PspPort` + `SimulatorChargeAdapter` exist (create path). `audit_log.actor_key_id` is nullable
  (V110) — scheduler/reconciler audit rows use NULL, never a fabricated key.
- Outbox/audit shapes: `payments.outbox` (V105) and `payments.audit_log` (V106) as-built — E5 writes
  rows, never alters them.
- Registered debts riding this epic: **DEBT-1** (Payment.restore validation — step 0, test-only),
  **DEBT-4** (journal coverage — Block 2 story, not this block).

## Sources of truth — binding

1. `tasks/expiration-reconciliation-e5-spec.md` §1–§5, §8 (§6/§7 are Block 2).
2. `tasks/expiration-reconciliation-e5-design-seed.md` — pre-adjudicated (polling, ladder, V111,
   no-@Transactional-on-scheduled, no ShedLock, audit command names, late=true in envelope).
3. `tasks/expiration-reconciliation-e5-sequence.md` — order + playbooks P1–P6.
4. `AGENTS.md` §3.2/§3.3/§3.4/§4.1/§8/§9d · `docs/design.md` §5.1 · playbook §4 (scenarios).

## Step 0 — Rider: `test(payments): rejecting contract on Payment.restore (DEBT-1)`

Test-only commit. Prove the hydration seam rejects an invariant-violating snapshot (corrupt state →
exception, never a silent invalid aggregate) — construct the corrupt snapshot at the seam the
adapter would deliver. If a REAL validation gap needs main-code changes: STOP-and-report with the
diagnosis (do not widen silently). Cite the DEBT-1 row.

## Step 1 — `feat(payments): reconciliation columns and pending-expires index (E5 V111)`

Migration exactly per spec §2 (next_reconcile_at, reconcile_attempts, partial index). Forward-only,
expand-only. IT harness proves it applies on real PG.

## Step 2 — `feat(payments): expiration of due pending payments (E5)`

Domain first (TDD, Clock-injected): expiration decision pure. Then scheduler wiring in `apps/api`
(spec §3: gated, fixed-delay, single-threaded, NOT transactional at the scheduled method,
per-payment tx, conditional UPDATE, `payment.expired` outbox row, audit `expire_payment`).
`ExpirationSchedulerIT` green (spec §7.1: due/not-due/race-no-op).

## Step 3 — `feat(payments): reconciler polls psp truth and confirms (E5)`

`PspPort.getCob(txid)` + adapter (GET `/cobs/{txid}`, WireMock); reconciler use case per spec §4
(scan due, PSP PAID→confirm `late=false`, EXPIRED→local expire, ACTIVE→ladder scheduling);
`ReconcilerConfirmIT` (spec §7.2 — scenario 26, webhook suppressed, idempotent rerun).

## Step 4 — `feat(payments): resurrection of expired payments via reconciliation (E5)`

Shared confirm/resurrection path per spec §5 (conditional transitions, `late=true`, audit
`confirm_from_reconciliation`, amount-mismatch guard). `ReconcilerResurrectionIT` (spec §7.3 —
scenarios 11 + 27: exactly-once under concurrent confirm via barrier; late webhook rejected AND
reconciler confirms).

## Non-negotiables & stop conditions

- Env §4.1 names/defaults EXACTLY; no other new names. Defaults false.
- Every state transition conditional (`WHERE status='…'`); loser re-reads and no-ops.
- Injected Clock; zero `Thread.sleep` in tests; Awaitility only for broker-bound outcomes;
  ITs drive schedulers via runOnce-style methods — deterministic, CI-proof.
- Zero touches: `modules/ledger`, `modules/notifications`, `apps/psp-simulator`; payments main
  changes only where a story demands (port method, use cases, adapter method); pom additions
  disclosed in the commit message that adds them.
- STOP-and-report on: any felt need for locks (ShedLock/advisory), any schema change beyond §2,
  any red you cannot explain in writing, any docs-vs-config divergence (P5).

## Handoff report (API-audited)

Step 0–4 commit shas + messages; test names + run pairs (number AND id) INCLUDING reds; the
conditional-UPDATE SQL quoted; the ladder application quoted; grep `Thread.sleep` = 0 with commit id;
exact head sha. Then stop. On verified audit: Block 2 (give-up, scenarios 9/10, DEBT-4 auditor,
docs + E5 flip) is commissioned.
