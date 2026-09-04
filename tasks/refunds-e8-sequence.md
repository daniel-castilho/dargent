# E8 Sequence — Refunds

Order, global rules, failure playbooks. The backlog says WHAT; this says in which order and what to
do when reality pushes back.

## Execution order (strict)

```
Block 1
  step 0: rider — DEBT-5 barrier (ctor check + DB deferred barrier)  [before any refund line exists]
  step 1: S1 V112 refunds table (MigrationIT leg)
  step 2: S2 refund domain/use case (TDD: D8 property tests first)
  step 3: S3 REST endpoint (contract ITs) + S4 balance-guard port
  step 4: S5 refund.created flow + RefundFlowIT (golden vector [3]+[4])
  step 5: block-1 handoff
Block 2 (prompt issued after Block 1 audit)
  step 6→8: S6 races + insufficient-balance IGNORED → S7 auditor extension → S8 docs + flip
```

Each step = at least one commit; every push green; reds cited (P1); pairs number AND id.

## Global rules

1. **TDD**: D8 fee-reversal math and the Σ-refunds guard are pure domain — property tests first
   (jqwik where the house already uses it); adapters get IT coverage, never mock-DB tests.
2. **One transaction per refund command** (D17): lock → validate → insert → bump version → outbox.
   No outbox write outside the aggregate tx (E3 as-built pattern).
3. **Money is conditional**: `SELECT FOR UPDATE` on the payment; ledger drain is a conditional UPDATE
   (`AND balance >= drain`). Losers re-read and decide. No read-then-write on balances.
4. **Reversal = new journal** (4th-analysis seed): never edit/patch journal data; entries [3]+[4] are
   one new JournalEntry per `refund.created`, exactly-once via `event_id` UNIQUE.
5. **Module isolation**: `MerchantBalancePort` lives in payments; its adapter lives in `apps/api`
   (composition root) reading the ledger's existing query service. No payments→ledger import, no
   HTTP hop, no cross-schema JOIN anywhere.
6. **Account names as-built** (spec §5 mapping table). The ledger chart does NOT grow in E8
   (no new accounts — [3]/[4] reuse the three existing accounts; if you believe a new account is
   needed → STOP-and-report).
7. Injected Clock; zero `Thread.sleep`; zero `@Disabled`; races via `ExecutorService` + barrier at
   the contention point (playbook §3).
8. Commit message = diff; pom additions disclosed; forward-only migrations; next payments slot V112.

## Failure playbooks

### P1 — CI red on a push
No stacking. Reproduce/explain in writing; fix-forward; cite red AND green ids. Never force-push.

### P2 — Felt need to diverge (error codes, DDL, port shape, IT names, account mapping)
STOP before the diverging line; ask with the concrete alternative. Pre-adjudicated already: as-built
account mapping (§5), txid-overload DEFERRED (register), no new accounts, no new envs. Changing any
of these = new adjudication, not implementation detail.

### P3 — A test only passes by weakening/sleeping
STOP. Race scenarios pin threads with barriers at the exact contention point. A balance-guard test
that sleeps is hiding the nondeterminism production will feel.

### P4 — D8 rounding edge (fee does not divide evenly)
The math is integer cents: `feeReversal = floor(fee × refund / amount)` is the DEFAULT; the remainder
stays with the platform (never over-return to the merchant). Property test: `Σ feeReversal over
full repayment ≤ fee` and `= fee` when fully refunded. If a different rounding is felt-needed →
STOP-and-report (it changes revenue semantics — owner call).

### P5 — Docs vs config divergence (standing rule)
STOP, report exact lines. README flips refund language only at S8 with proof in hand.

### P6 — Scope creep (payouts, negative balances, multi-currency, refund webhooks to PSP)
All OUT (D10; PIX rail; v1 scope). The negative-balance question (settle-then-refund overdraw) is
handled by the adjudicated IGNORED+audit path — changing that is an owner decision via register.
