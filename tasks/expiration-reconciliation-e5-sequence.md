# E5 Sequence — Expiration, Resurrection & Reconciliation

Order, global rules, failure playbooks. Backlog says WHAT; this says in which order and what to do
when reality pushes back.

## Execution order (strict)

```
Block 1
  step 0: rider — DEBT-1 rejecting-contract test (Payment.restore)   [test-only commit]
  step 1: S1 V111 migration (columns + partial index)
  step 2: S2 expiration domain (TDD) → scheduler wiring → ExpirationSchedulerIT
  step 3: S3 PspPort.getCob (adapter + WireMock) → reconciler engine (TDD) → ReconcilerConfirmIT
  step 4: S4 resurrection legs → ReconcilerResurrectionIT
  step 5: block-1 handoff (pairs, greps, divergences)
Block 2 (prompt issued after Block 1 audit)
  step 6→8: S5 give-up → S6 late/replay legs → S7 coverage auditor → S8 docs + flip
```

Each step = at least one commit; every push green; reds cited (P1); pairs number AND id.

## Global rules

1. **TDD**: pure domain first (ExpirationPolicy, ReconcileDecision — Clock-injected), adapter seams
   covered by ITs on real PG + WireMock PSP.
2. **Conditional UPDATEs arbitrate everything** (AGENTS §3.2): expiration, confirm, resurrection,
   claim-via-`next_reconcile_at` scheduling. No locks, no ShedLock, no advisory locks in E5.
3. **Schedulers live in `apps/api`**, gated by env §4.1 (default false), single-threaded, fixed-delay;
   the scheduled method is NOT transactional; per-payment tx via `TransactionTemplate`.
4. **Injected `Clock`**; time travel in tests; zero `Thread.sleep`; Awaitility only for
   broker/outbox-bound outcomes.
5. **E5 only writes rows** (payments, outbox, audit). No delivery logic (E9), no bus mechanics (E6).
6. **Env names** exactly spec §4.1; defaults are contract.
7. Module mains stay Spring-free; schedulers/auditor wiring in `apps/api` (composition root).
8. Commit message = diff; no artifact referenced before it exists; pom additions disclosed.
9. Docs honesty (§10): the README recast sentence flips to present tense only at S8, after proof.

## Failure playbooks

### P1 — CI red on a push
Do not stack commits. Reproduce or explain in writing (test, assertion, suspicion). Fix-forward;
cite red AND green run ids in the handoff. Never force-push, never rewrite history.

### P2 — Felt need to diverge (env name, DDL, flow, IT name, backoff numbers)
STOP before writing the diverging code; ask with the concrete alternative. The seed's decisions
(polling, ladder, V111, no-ShedLock) are pre-adjudicated — changing them is a new adjudication,
not an implementation detail.

### P3 — A test only passes by weakening an assert or sleeping
STOP. Race scenarios must be deterministic (conditional UPDATEs + barriers/interleaving), never
timing-based. A reconciliation test that needs a sleep is hiding a nondeterminism that production
will feel.

### P4 — WireMock/PSP flake in reconciler ITs
One rerun, both runs cited. Second flake of the same test = STOP with logs; harden the stub
(canned responses, deterministic state machine) before proceeding.

### P5 — Docs vs config divergence (standing rule)
README/epics/AGENTS vs code reality mismatch → STOP, report exact lines. The TD-8 lesson.

### P6 — Scope creep pressure (republish tool, DLQ-driven reconcile, payouts)
Out of charter. Reconciler is PSP-polling only; republish/DLQ redrive is E9; payouts are E7/E8.
Demands go to the owner; do not pre-implement "while we're here".
