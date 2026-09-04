# E9 Sequence — Delivery Hardening

Order, global rules, failure playbooks. Backlog = WHAT; this = in which order, and what to do when
reality pushes back.

## Execution order (strict)

```
Block 1
  step 1: S1 exhaustion contract (TDD on the use case: matrix first)
  step 2: S2 requeue endpoint (+ OutboxExhaustionIT completing scenario 19's spine)
  step 3: S3 republish tool + S4 scenario-20 no-double-journaling proof
  step 4: block-1 handoff
Block 2 (prompt issued after Block 1 audit)
  step 5→6: S5 DLQ recipes → S6 docs + E9 flip + M3 flip + citation
```

Each step = at least one commit; every push green; reds cited (P1); pairs number AND id.

## Global rules

1. **TDD**: the exhaustion matrix and requeue semantics are use-case logic — unit tests first on
   fakes; adapter SQL covered by ITs on real PG; endpoint by HTTP ITs.
2. **Frozen ladder**: E6's backoff numbers are untouchable constants (P2 if ever felt otherwise).
   E9 adds the CEILING, not a new rhythm.
3. **Every transition conditional** (`WHERE status='...'`); losers re-read; double-requeue and
   lost-race markSent keep today's semantics.
4. **Republish mints, never mutates**: new rows, new event_ids (ONE rule per spec §5), originals
   untouched; consumers' dedupe is the replay's correctness proof (scenario 20).
5. **Audit with real actors** for human actions (requeue/republish use the API-key principal —
   the SYSTEM sentinel stays for machine paths only).
6. Injected Clock; zero sleeps/disabled; module mains Spring-free; wiring in `apps/api`.
7. Commit message = diff; no artifact referenced before it exists; zero migrations expected
   (V105 already carries EXHAUSTED + next_attempt_at) — any felt migration = stop-and-report.

## Failure playbooks

### P1 — CI red on a push
No stacking. Explain in writing (test, assert, suspicion); fix-forward; cite red AND green ids.

### P2 — Felt need to touch the ladder/backoff numbers or add env outside §4.1
STOP before the line. The ladder is E6's ratified behavior; maxAttempts ceiling and §4.1 names are
the only E9 knobs. Anything else = owner adjudication.

### P3 — A test only passes by weakening/sleeping
STOP. Exhaustion is deterministic (attempt counter + forced publisher failure); races pin with
barriers at the claim/mark contention points.

### P4 — Republish semantics pressure (reuse event_ids, mutate SENT rows, replay without window)
All rejected by contract. Scenario 20's proof EXISTS because republish mints new identities. If a
recovery case seems to need identity reuse → stop-and-report; that is an owner decision.

### P5 — Docs vs config divergence (standing rule)
STOP, report exact lines. S6 flips language only with proof in hand (maturity-carry rule).
The S6 riders (TD-26 annotations, refund×PSP limitation note — spec §7.1) are owner-approved
scope: annotate claims with (M4)/(M5); deleting a claim is a defect.

### P6 — Scope creep (DLQ auto-redrive loops, webhook-level delivery, scheduling UI)
Out. Redrive stays a documented human decision; E9 hardens, does not automate operations.
