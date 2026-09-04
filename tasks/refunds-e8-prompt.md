# Epic Prompt — E8: Refunds (partial/total, fee reversal, balance guard)

**Issued:** 2026-09-02 · **Owner decision:** package commissioned this channel ("próximo movimento").
**Executor:** the AI Software Engineer · **Auditor:** the governance side (API-audited: messages vs
diffs, run pairs number AND id, reds always cited). **Push is the owner's action.**

## What E8 is

The money loop closes in one direction today (paid → confirmed → journal → settle). E8 adds the way
back, honestly:

- **REST refunds**: `POST /v1/payments/{txid}/refunds` — partial or full (default), N partials per
  payment, `Σ refunds ≤ amount` under a pessimistic payment lock (design D17), proportional fee
  reversal (D8: refund 40% → 40% of the fee returns to the merchant), idempotency keys reused (E3
  infra), API-key tenant from principal (AGENTS §3.7), canonical error envelope;
- **`refund.created` event** (design §7.2: carries amount / feeRefund / netRefund) → outbox → bus;
- **Ledger entries [3]+[4]** — the consumer's FIRST second posting template: [3] Dr
  `merchant:{id}:available` / Cr `payments:processing` (money returns toward the payer); [4] Dr
  `fees:revenue` / Cr `merchant:{id}:available` (proportional fee reversal — D8). Reversal = a NEW
  journal entry per event (never an edit); FIFO per-payment ordering (MessageGroupId = txid) means a
  refund always journals after its confirm;
- **Balance guard, two layers**: payments-side guard at command time (port read of the merchant's
  ledger available, adapter wired in the composition root — module isolation preserved) + ledger-side
  final arbitration (conditional drain `balance ≥ net drain`; 0 rows → the event is IGNORED with note
  + audit row, projection never goes negative);
- **DEBT-5 barrier lands here** (the guard becomes load-bearing): `JournalEntry` refuses unbalanced
  entries in the constructor + a Postgres-side barrier on `ledger.postings` — the ledger refuses
  impossible state by itself, before any refund line exists;
- **DEBT-4 auditor extends**: REFUNDED payment with no refund journal (or refund journal with no
  REFUNDED payment) = dangling-money alarm, same two-SELECTs + diff mechanics.

**Scope boundary (D10): payouts are NOT in v1.** "Payout as reconciliation with a real cash account"
stays a design seed for a future epic. E8 flips its own epic row only — **M3 stays ◐ until E9**.

## Sources of truth — binding, in this order

1. `docs/design.md` — D8 (proportional fee return), D17 (Σ refunds ≤ amount; lock → validate →
   insert → bump version → outbox), §4.x worked example (entries [3]+[4] with the 100/0.40/40
   arithmetic — the golden vector for ITs), §7.2 (`refund.created` routing).
2. `docs/epics.md` — E8 brief ("Partial/total under pessimistic payment lock, rides on E1's
   `refund()` transition, proportional fee reversal (D8), ledger entries [3]+[4], REST endpoint.
   Proves scenarios 12, 19, 23").
3. `tasks/refunds-e8-spec.md` — exact contracts (DDL V112, API, event payload, ledger branch,
   guards, IT names). **Account names follow the AS-BUILT E7 chart** (`payments:processing`,
   `fees:revenue`, `merchant:{id}:available`) — the design example's typed names
   (`ASSET:PSP_CLEARING`, `REVENUE:PLATFORM_FEES`) map onto them; the mapping is in spec §5.
4. `AGENTS.md` §3.2/§3.3/§3.4/§4.1/§8 (DEBT-5 row)/§9d · playbook §4 (scenarios 12, 19, 23).
5. `internal-notes` seeds reflected in the spec: reversal-not-edit; txid-overload fix DEFERRED
   (register note — settlement keeps its idempotency-key-in-txid for now; revisit with payouts).

**Standing rule: if docs and config diverge, STOP.** Divergences → stop-and-report BEFORE diverging.

## Non-negotiables

- Env names: **no new names are expected in E8.** If you believe one is needed → stop-and-report
  (§4.1 governs). Existing names never change.
- Idempotency for refunds reuses the E3 idempotency infrastructure (same key semantics: same key +
  same body → replay; different body → 409). No new store.
- Every money mutation stays conditional/locked: payment row `SELECT FOR UPDATE` (D17); ledger drain
  via conditional UPDATE (`AND balance >= drain`). No optimistic read-then-write on money.
- Reversal entries are new journals referencing the original fact — never UPDATE/DELETE on journal
  data (E7's append-only grants unchanged).
- TDD on pure domain (D8 math is property-test material); WireMock/Testcontainers at the seams;
  injected Clock; zero sleeps; zero `@Disabled`; commit message = diff; migrations forward-only
  (next payments slot is **V112** — V107/V109 are gap history; ledger migrations untouched).
- Zero lines in `modules/notifications`, `apps/psp-simulator`; notifications records `refund.created`
  automatically (proven — records every type).

## Blocks

- **Block 1** — DEBT-5 barrier (step 0), V112 refunds table, refund use case (TDD), REST endpoint +
  guards, `refund.created` flow, core ITs (scenarios 12 leg + full-flow golden vector).
  Prompt: `tasks/refunds-e8-execution-prompt-block1.md`.
- **Block 2** — ledger-side concurrent races (scenarios 19/23), insufficient-balance IGNORED path,
  DEBT-4 auditor extension, docs + E8 row flip + citation. Prompt issued after Block 1's audit.

## Handoff (API-audited, per block)

Commit shas + messages; test names + run pairs (number AND id); greps with commit ids; anything NOT
done with reason; clarifications asked BEFORE diverging. Then stop.
