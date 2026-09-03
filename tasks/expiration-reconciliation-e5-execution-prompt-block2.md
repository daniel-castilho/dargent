# Execution Prompt — E5 Block 2: Give-up + Scenarios 9/10 + DEBT-4 Auditor + Docs & Flip

**Issued:** 2026-09-02 · **Executor:** the AI Software Engineer · **Auditor:** the governance side
(API-audited). **Gate status:** Block 1 APPROVED by audit — Block 2 is commissioned. **Push is the
owner's action.**

## Where you are starting from (audited facts)

- main = `8128eb573c`, runs #125–#129 ALL GREEN (full chain in the canonical audit; DEBT-1 CLOSED).
- Block 1 landed as **V111** (V109 was free — skip adjudicated KEEP; V107/V109 are gap history;
  **next payments migration is V112** if you need one — this block should need none).
- Pending doc syncs you are now OWNERS of (the governance workspace files will land via the owner;
  your repo copies must match by S8): spec §2/§4 amended for TD-21 + V111 numbering note; register
  rows TD-21 + V-NUM-E5; Q4 (restore() validation — already as-built) and Q5 (scan) addenda.

## Steps (order strict; every push green; pairs number AND id; reds always cited)

### Step 5 — `test(payments): reconciler gives up past the resurrection window (E5 S5)`
`ReconcilerGiveUpIt` (spec §7.4): past `expires_at + DARGENT_RECONCILER_GIVE_UP_HOURS` → no confirm;
`next_reconcile_at = NULL` (conditional); audit `reconciliation_window_expired`; applies to PENDING
and EXPIRED alike (TD-21). Time-travel Clock, no sleeps.

### Step 6 — `test(payments): late and replay reconciliation legs (E5 S6, scenarios 9 and 10)`
Scenario 9 leg: out-of-order/late event → final state consistent, unknown/late intake → `IGNORED`
persisted (use-case altitude where cheaper; IT leg where the seam demands). Scenario 10 leg: replaying
`payload_raw` produces the same result (idempotent confirm, zero double side effects). Inside
`ReconcilerResurrectionIT` or a small dedicated IT — your call, names house-style.

### Step 7 — `feat(api): journal coverage auditor detects dangling money states (E5 S7, DEBT-4)`
Composition root ONLY (`apps/api` — both schemas visible there; module mains untouched). Gated
(`DARGENT_JOURNAL_COVERAGE_ENABLED` default false, `DARGENT_JOURNAL_COVERAGE_SCAN_MS`), runOnce-driven
in ITs. Gap queries per spec §6 (CONFIRMED payment without POSTED `ledger.events` anchor AND reverse);
join keys defined from the as-built `ledger.events` columns — if ambiguous, STOP-and-report with both
candidate queries. Gap → WARN + payments audit `journal_coverage_gap` (aggregate_id/request_id
columns — no schema change). **Detect-and-alarm; never auto-repair.** `JournalCoverageAuditorIT`
green (simulated dangling flagged; clean state silent).

### Step 8 — `docs(e5): sync spec as-built, fill matrix, flip epic row (E5 S8)`
1. Repo spec (`tasks/expiration-reconciliation-e5-spec.md`): §2 V111 numbering note (V109 was free;
   adjudicated keep), §4 TD-21 scan + initialization + give-up-as-built, §7 give-up/auditor IT names
   as landed, §10 matrix rows filled with pairs (number AND id) for S0–S8.
2. Register copy in repo (`tasks/create-webhook-remediation-e3r-spec.md`): TD-21 + V-NUM-E5 rows land
   (governance-authored text is in the owner's workspace — sync from the owner's push if it landed,
   else from the handoff below).
3. README: the recast sentence becomes present tense ONLY here — "future reconciliation job (E5, not
   started)" → reconciler live (with the guarantee table); money-flow "reconciler (E5, future)" → live.
   No other present-tense creep.
4. `docs/epics.md`: E5 row ✅ (same change set, epics conventions, chain cited). **M3 stays ◐**
   (waits E8+E9).
5. Flip + citation: last content commit → exactly one citation commit recording the pair (#57/#67
   precedent: the citation run is unregistered).

## Non-negotiables

Scope: `apps/api` (auditor + ITs), `modules/payments` tests only (Steps 5–6 should touch NO prod
code — if a step needs main-code changes, STOP-and-report), `tasks/` docs. Env §4.1 names only.
Zero ledger/notifications/psp touches. Conditional UPDATEs; injected Clock; zero sleeps/disabled.
Commit message = diff.

## Handoff (API-audited)

Commit shas + messages; run pairs per step (number AND id) INCLUDING reds; the gap queries quoted;
grep `Thread.sleep` = 0 with commit id; exact head sha. Then stop — E5 closes on this channel's
audit; M3 remains open for E8.
