# Payment Domain & State Machine E1 — Implementation Sequence

## Epic E1 — Rich Payment Entity, Value Objects, Fee Math & the Optimistic-Lock Persistence Seam

**Companions:** `payment-domain-e1-spec.md` · `payment-domain-e1-backlog.md`
**Rule:** Complete each step's acceptance and verification before starting the next. Do not invent E2+ scope.
**Process rule:** Steps 1–3 are test-first: write the failing tests, watch them fail for the right reason,
then implement. A step committed without a prior red is a process violation, not a shortcut.

---

## Global execution rules

1. Small reviewable vertical commits; story-sized, never the epic as one drop.
2. Read the story acceptance before coding; add tests with the production change.
3. No dependency beyond spec §4 without explicit approval.
4. A red `main` baseline stops work; green-baseline regressions are diagnosed before new commits.
5. After each step: update backlog checkboxes, note deviations here, keep docs truthful.
6. Every commit keeps the M0 gates green: ArchUnit, boundary script, `mvn -B verify`.

### Fast verification used throughout

```bash
mvn -B -pl modules/payments -am test          # unit layer of this epic (fast)
```

### Full verification (unit + adapter ITs, real PostgreSQL)

```bash
mvn -B verify                                 # whole reactor; Failsafe runs *IT
mvn -B verify -pl modules/payments -am        # epic-scoped: unit + ITs of payments
```

### Boundary verification (after any structural change)

```bash
bash scripts/check-boundaries.sh
```

---

## Step 0 — Baseline lock

### Stories: S0-equivalent (no code)
### Actions
1. Confirm `main` CI green (M0 closure run is the reference) and the local reactor verifies:
   `mvn -B -pl modules/payments -am test` green with the M0 seeds.
2. Confirm approved dependency additions (spec §4) and nothing else pending approval.
3. Read spec §5 (state table), §7 (fee math) and lessons #1/#2 before writing any test.

### Done when
- Local verification green; no unanswered spec questions.

---

## Step 1 — Value objects and fee math (S1, S2, S3)

### Actions
1. **Tests first** (`TxidTest`, `EndToEndIdTest`, `FeeBreakdownTest` incl. jqwik properties) — watch them
   fail to compile/fail for the right reason.
2. Implement `Txid` + `TxidGenerator`, `EndToEndId`, `BpsRate`, `FeeBreakdown` (formulas exactly per spec §7).
3. Commit per VO (`feat(payments): txid value object and generator`, …).

### Done when
- Unit suite green including properties; rounding rule documented in the class javadoc (down / merchant-favorable).

### Verify
```bash
mvn -B -pl modules/payments -am test
```

---

## Step 2 — State machine core (S4, S5)

### Actions
1. Write `PaymentTest` as a **table-driven** suite over spec §5: for each (from → to) cell assert either the
   legal outcome (state, version bump, event payload) or `InvalidTransitionException`. Include terminal-state
   rows and the double-confirm row.
2. Implement `Payment` (factory + transition methods + events collector + version field) and the two typed
   exceptions. Zero setters; intention-revealing method names; no `Instant.now()` inside the entity —
   time always arrives as parameters (playbook `Clock` discipline).
3. Add the resurrection tests (`EXPIRED → CONFIRMED`, `late=true`, event carries it).

### Done when
- Transition table 100% green; ArchUnit purity untouched (domain has zero framework imports).

### Verify
```bash
mvn -B -pl modules/payments -am test && bash scripts/check-boundaries.sh
```

---

## Step 3 — Port and in-memory fake (S6)

### Actions
1. Define `PaymentRepository` port with the lost-race contract in javadoc (spec §6).
2. Implement `InMemoryPaymentRepository` in test scope + **shared contract tests** (abstract base suite the
   JPA adapter will also extend in Step 4): save/find round-trip, version match → true, stale → false,
   state visible after winner update.

### Done when
- Contract suite green on the fake; contract documented on the interface.

### Verify
```bash
mvn -B -pl modules/payments -am test
```

---

## Step 4 — Persistence: migration, JPA entity, adapter (S7, S8)

### Actions
1. `V102__create_payments_table.sql` exactly per spec §8; add the `description` row to design.md §5.1 in the
   same commit (`docs(data): sync payments table with V102`).
2. Add `spring-boot-starter-data-jpa` to the payments module (compile scope, spec §4 approval already granted).
3. `PaymentEntity` + `PaymentMapper` + `PaymentJpaAdapter`; `OptimisticLockingFailureException` → `false`.
4. Wire the adapter's test configuration (minimal, module-local).

### Done when
- Module compiles; adapter extends the S6 contract suite (compilation proves the shared contract);
  boundary gates still green.

### Verify
```bash
mvn -B -pl modules/payments -am test && bash scripts/check-boundaries.sh
```

---

## Step 5 — Integration proofs on real PostgreSQL (S9, S10)

### Actions
1. Add Testcontainers test deps to the payments module (spec §4); write `PaymentJpaAdapterIT`: container
   `postgres:16-alpine`, Flyway on `classpath:db/migration/payments`, extend the shared contract suite.
2. Write `PaymentConcurrentTransitionIT` per spec §9: 8 threads, `CyclicBarrier`, one winner, loser re-read;
   playbook-compliant name; no sleeps.
3. Run the full reactor verify.

### Done when
- Both ITs green locally under `mvn -B verify -pl modules/payments -am`; race IT stable across 5 consecutive runs.

### Verify
```bash
mvn -B verify -pl modules/payments -am
for i in 1 2 3 4 5; do mvn -B verify -pl modules/payments -am -Dit.test=PaymentConcurrentTransitionIT || exit 1; done
```

---

## Step 6 — Full pipeline green and closure (S11)

### Actions
1. `mvn -B verify` on the whole reactor; push; watch CI green (unit + ITs in one run).
2. Fill `tasks/e1-acceptance-matrix.md` (evidence: run link, test class names, matrix of transition rows).
3. Sync docs: design.md §5.1 (done in Step 4), epics.md E1 → ✅, CHANGELOG, lessons (anything the race IT
   taught — e.g., barrier placement, version-guard subtleties).
4. Final commit: `docs(e1): close payment domain epic — acceptance matrix evidenced`.

### Done when
- CI green on `main`; matrix has zero `pending`; docs truthful.

### Verify
```bash
grep -c pending tasks/e1-acceptance-matrix.md   # expect 0
git status --porcelain                           # expect empty
```

---

## Failure playbooks (stop conditions)

| Symptom | Stop and do this |
|---|---|
| Race IT flaky (winner count varies) | The barrier or the version guard is wrong — re-read spec §9; never add sleeps; quarantine is forbidden (money-path test) |
| `OptimisticLockingFailureException` escapes the adapter | Contract violated (spec §6): catch at the adapter seam, return `false`, unit-test it |
| ArchUnit flags domain imports after Step 4 | JPA types leaked into `domain/` — the mapper owns all conversions; fix the leak, never weaken the rule |
| Flyway location not picked up in the module IT | Locations must point at `classpath:db/migration/payments`; check test config before touching migration names |
| jqwik property fails on an edge | Treat as a real bug in the formula first; adjust the property only with a documented reason in the test |
| CI slower than M0 significantly | Check the module IT reuses one container per class (not per test); singleton pattern is E5+ — acceptable to be class-scoped now |

---

## Deviation log

| Date | Step | Deviation |
|---|---|---|
| 2026-08-28 | S3 | `FeeBreakdown.feeReversalFor` is an **instance method** taking only `refundCents` (spec formula requires the original amount, which comes from this breakdown's `amount`/`fee`). The backlog's stated signature `(refundCents, originalFeeCents)` would be ambiguous without the amount; a static `feeReversal(long, long, long)` keeps the pure formula unit-testable. |
