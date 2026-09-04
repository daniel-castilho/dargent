# Execution Prompt — E8 Block 1: DEBT-5 barrier + Refund core + golden-vector flow

**Issued:** 2026-09-02 · **Executor:** the AI Software Engineer · **Auditor:** the governance side
(API-audited: messages vs diffs, run pairs number AND id, reds always cited). **Push is the owner's
action.**

## Where you are starting from (audited facts — cite pairs, never memory)

- main = `ef00134277a13a2566bd45e81484f778b2b09110` (E5 citation), runs #127–#136 green. **E5 ✅**
  closed; M3 ◐ (E8 + E9 remaining). Milestones: M0/M1/M2 ✅.
- `Payment.refund(Money, Money, Instant)` EXISTS (E1 as-built) — status gate
  (CONFIRMED/PARTIALLY_REFUNDED), positivity, remainder math. Use it; do not duplicate it.
- Design anchors: D8 (proportional fee return), D17 (lock → validate → insert → bump → outbox),
  §4.x golden vector (100.00, fee 1.00, refund 40.00, reversal 0.40 → available 59.40), §7.2
  (`refund.created` → ledger entries [3]+[4] + notifications).
- Payments migrations @HEAD: ..., V110, V111 (E5). **Next slot: V112.** V107/V109 are gap history —
  never reuse; if V112 is taken when you start, take the next free AND disclose (V-NUM-E5 precedent).
- DEBT-5 (AGENTS §8): the ledger holds no unbalanced-entry barrier anywhere before the write —
  this block lands it BEFORE any refund line exists (adjudicated sequencing).
- Landed adjudications you must honor: as-built account mapping (spec §5), floor rounding (P4),
  fail-closed balance port (§3.4), txid-overload DEFERRED (register — do NOT fix settlement here),
  no new envs, no new ledger accounts, no ledger migration.

## Sources of truth — binding

1. `tasks/refunds-e8-spec.md` §1–§5, §7 (§6/§8 are Block 2) — the contracts.
2. `tasks/refunds-e8-sequence.md` — order + playbooks P1–P6 (P4 = D8 rounding).
3. `tasks/refunds-e8-backlog.md` — S0–S5 acceptance.
4. `AGENTS.md` §3.2/§3.3/§3.4/§4.1/§8/§9d · `docs/design.md` D8/D17 + §4.x worked example ·
   playbook §3 (barriers) / §4 (scenario 12).

## Steps

### Step 0 — `test(ledger): balanced-entry barrier (DEBT-5)`
1. `JournalEntry` ctor: reject Σ debits ≠ Σ credits, < 2 postings, any amount ≤ 0 — typed domain
   exception per house pattern. Unit tests: balanced 2/3-line entries pass; the classic cheats
   (padding pair, sign hack, single line) fail with the specific exception.
2. DB barrier: test-local deferred constraint/trigger on `ledger.postings` (E5-style inline DDL
   first); an unbalanced journal at the DB layer must fail to commit. If you conclude the barrier
   must be a REAL ledger migration to be meaningful → STOP-and-report with the rationale
   (E8 is payments-led by plan; a ledger migration is an adjudication).
3. Green BEFORE any refund code lands (the barrier precedes the traffic — adjudicated order).

### Step 1 — `feat(payments): refunds table and reconciliation-free refund record (E5-free V112)`
V112 exactly per spec §2 (checks incl. `net = amount − feeReversal`; index). MigrationIT leg on
real PG. No other schema changes.

### Step 2 — `feat(payments): refund use case with proportional fee reversal (E8)`
TDD: D8 property tests first (floor rounding; Σ ≤ fee; = fee at full repayment; remainder stays
with the platform), then the Σ-refunds guard, then the transactional use case per spec §3
(lock → status → remainder → balance-guard port call → D8 → `Payment.refund` → insert → bump
version → outbox `refund.created`). Port `MerchantBalancePort` defined in payments (interface only
this step; fake-driven tests).

### Step 3 — `feat(payments,api): refund endpoint with idempotency and balance guard (E8)`
REST per spec §4 (auth, §3.7 tenant, canonical errors incl. the adjudicated new codes, camelCase
wire). `MerchantBalancePort` adapter wired in `apps/api` reading the ledger's existing balance
query (no cross-module import, no HTTP hop; fail-closed per §3.4). `RefundEndpointIT` +
`RefundBalanceGuardIT` green (spec §7.2/§7.3).

### Step 4 — `feat(ledger): refund.created journal entries [3]+[4] (E8)`
Ledger consumer branch per spec §5 (ONE reversal journal per event; as-built accounts; conditional
available-drain with `balance >=` final arbitration → IGNORED + audit `refund_skipped_balance` on
0 rows). `RefundFlowIT` green — the golden vector end-to-end: confirm 100.00/fee 1.00 → refund
40.00 → Dr available 40/Cr processing 40 + Dr fees 0.40/Cr available 0.40 → available exactly
59.40, proof ok, redelivery no-op (spec §7.1). Notifications records it unchanged.

## Non-negotiables & stop conditions

- No new env names; no new ledger accounts; no ledger migration (S0 is test-local DDL — real
  migration ⇒ STOP-and-report); no settlement changes (txid-overload DEFERRED).
- Every money mutation conditional/locked (payment row FOR UPDATE; balance drain `balance >=`).
- Reversal journals are NEW entries; journal data never updated/deleted; E7 append-only grants
  untouched.
- Injected Clock; barriers at contention points; zero sleeps/disabled; module mains Spring-free.
- Zero lines: `modules/notifications`, `apps/psp-simulator`. Payments prod changes only where a
  story demands. Commit message = diff; pom additions disclosed.
- STOP-and-report on: any red you cannot explain in writing; any D8 rounding alternative (P4);
  any felt need for a negative-balance policy (P6 — that is an owner decision); any
  docs-vs-config divergence (P5).

## Handoff report (API-audited)

Step shas + messages; test names + run pairs (number AND id) INCLUDING reds; the D8 property test
names quoted; the conditional-drain SQL quoted; the golden-vector balances quoted from the IT;
greps (§8) with commit ids; exact head sha. Then stop. On verified audit: Block 2 (races, IGNORED
path, auditor extension, docs + E8 flip) is commissioned.
