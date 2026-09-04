# Execution Prompt — E9 Block 1: Exhaustion + Requeue + Republish (+ scenario 20 proof)

**Issued:** 2026-09-03 · **Executor:** the AI Software Engineer · **Auditor:** the governance side
(API-audited: messages vs diffs, run pairs number AND id, reds always cited). **Push is the owner's
action.**

## Where you are starting from (audited facts — cite pairs, never memory)

- main = `694760235da6a688ed67176630a0a153bd98cf16` (E8 citation, run #148 `33832352522` green).
  **E8 ✅ closed; M3 ◐ with E9 as the last epic.**
- **E6 left the relay 90% built** (this is a finishing epic, not a building one):
  `OutboxDeliveryUseCase` already claims with `FOR UPDATE SKIP LOCKED` filtered by
  `next_attempt_at <= now`, retries with the 30s/2m/5m ladder (`backoff()` — FROZEN), marks
  SENT/FAILED conditionally (lost-race safe), purges SENT retention; `Policy.maxAttempts` is
  declared **"unbounded in E6 — E9 owns EXHAUSTED"** — that comment is your work order.
- V105 already has `EXHAUSTED` in the CHECK and every column §2 needs — **zero migrations is the
  adjudicated target** (a felt migration = stop-and-report).
- `SYSTEM_AUDIT_ACTOR` sentinel exists for machine paths; requeue/republish are HUMAN actions —
  real API-key actor (E8's skip-audit pattern shows the mechanism).
- **E9 house rules born upstream (standing):** grep-before-claim on as-built statements; IT names
  match failsafe `**/*IT.java` exactly; camelCase wire; §3.7 tenant rules; flow-level adversarial
  read (who does NOT get seen by each query).

## Sources of truth — binding

1. `tasks/delivery-hardening-e9-spec.md` §1–§5, §7 (S5/S6 are Block 2).
2. `tasks/delivery-hardening-e9-sequence.md` — order + P1–P6 (P2: the ladder is frozen; P4:
   republish mints, never mutates).
3. `tasks/delivery-hardening-e9-backlog.md` — S1–S4 acceptance.
4. `AGENTS.md` §3.2/§3.3/§3.4/§4.1/§9d · playbook §4 scenarios 18–20 · design §7.2/§7.3.

## Steps

### Step 1 — `feat(payments): bounded delivery — EXHAUSTED at max attempts (E9)`
TDD: the exhaustion matrix first (attempt 1→30s, 2→2m, 3→EXHAUSTED at default maxAttempts=3;
EXHAUSTED never claimed; conditional transition; lost-race no-op). `Policy.maxAttempts` wired to
`DARGENT_RELAY_MAX_ATTEMPTS` (§4.1, default 3). Writer-bug rows (invalid payload) KEEP the
leave-PENDING+log behavior — code bugs are not delivery failures.

### Step 2 — `feat(api): audited outbox requeue endpoint (E9)`
`POST /v1/outbox/{id}/requeue` per spec §3 (conditional EXHAUSTED→PENDING, attempts reset, audit
`outbox_requeued` with the real principal, admin-gated via `DARGENT_OUTBOX_ADMIN_KEY` — absent env
= endpoints 404-hidden). `OutboxExhaustionIT` + `OutboxRequeueIT` green — **scenario 19 complete
end-to-end**: backoff → FAILED → EXHAUSTED → audited requeue → SENT.

### Step 3 — `feat(api): outbox republish tool with deterministic replay ids (E9)`
`POST /v1/outbox/republish` per spec §4 (window on `published_at`, ≤30d, batch ≤500, new rows with
**`{eventId}-r{n}` deterministic ids** — re-running the tool is idempotent at consumers; originals
untouched; audit `outbox_republished`). `OutboxRepublishIT` green (minting, bounding, empty window,
auth negatives).

### Step 4 — `test(api,payments): republish replay does not double-journal (scenario 20, E9)`
The proof leg: confirmed payment → journal 1 row → republish its window → second delivery consumed
→ journal STILL 1 row, notifications 1, balances unchanged (extends the RefundFlow harness pattern).
Scenario 20 green in CI.

## Non-negotiables & stop conditions

- Env §4.1 exactly (two new names total); E6 ladder frozen; EXHAUSTED earned only by the counter;
  republish mints with deterministic ids (P4 if anything else is felt).
- Every status mutation conditional; human actions audited with real actors; module mains
  Spring-free; injected Clock; zero sleeps/disabled; barriers at claim/mark contention.
- Zero lines in ledger/notifications/psp-simulator prod; payments prod only where a story demands
  (the exhaustion branch + port method); commit message = diff; zero migrations expected.
- STOP-and-report on: any red you cannot explain in writing; any ladder/number temptation (P2);
  any identity-reuse pressure (P4); any docs-vs-config divergence (P5).

## Handoff report (API-audited)

Step shas + messages; test names + run pairs (number AND id) INCLUDING reds; the exhaustion matrix
quoted; the conditional UPDATEs quoted; the salted-id rule shown twice (first run + re-run);
`grep EXHAUSTED` + `grep "balance_cents >=" ` outputs with commit ids; exact head sha. Then stop.
On verified audit: Block 2 (DLQ recipes + docs + E9 flip + M3 flip + citation) is commissioned.
