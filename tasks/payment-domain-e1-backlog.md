# Payment Domain & State Machine E1 — Backlog

## Epic E1 — Rich Payment Entity, Value Objects, Fee Math & the Optimistic-Lock Persistence Seam

**Priority:** P0
**All stories:** Must
**Companions:** `payment-domain-e1-spec.md` · `payment-domain-e1-implementation-sequence.md` · `ai-software-engineer-prompt-payment-domain-e1.md`

**Execution status:** opened 2026-08-28 after M0 closure (CI run #33217044326 green). Greenfield epic —
S1–S2 ☑, S3+ still ☐. Test-first process is mandatory for S1–S3 (prompt rule 1).

---

## Epic outcome

The `payments` module owns a rich, framework-free `Payment` aggregate whose state machine makes invalid
transitions unrepresentable, value objects that self-validate (txid, endToEndId, fee math in basis points
with property-tested invariants), and a persistence seam where the PostgreSQL row — not application hope —
arbitrates concurrent transitions. Every later money epic (E3..E8) builds on these contracts unchanged.

---

## Story map

```text
VALUE OBJECTS
S1   Txid (25 alnum) + secure generator
S2   EndToEndId (shape-validated, 32 chars)
S3   BpsRate + FeeBreakdown (fee math + proportional reversal, property-tested)

DOMAIN CORE
S4   Payment state machine (full transition table, domain events, resurrection)
S5   InvalidTransitionException + typed domain errors

PERSISTENCE SEAM
S6   PaymentRepository port + in-memory fake (lost-race semantics)
S7   V102 payments table migration (module-owned DDL)
S8   PaymentEntity + mapper + PaymentJpaAdapter (version-guarded update)

PROOFS
S9   PaymentJpaAdapterIT against real PostgreSQL 16 (Flyway module locations)
S10  Concurrent transition IT — exactly one winner (playbook scenario 13)

CLOSURE
S11  Docs sync (design.md §5.1 description column, epics.md, acceptance matrix, CHANGELOG)
```

---

## S1 — Txid value object + secure generator ☑

### Work
- [x] `Txid` record validating `^[A-Z0-9]{25}$` after uppercasing input; rejects null/wrong length/non-alnum
- [x] `TxidGenerator` interface + default `SecureRandom` impl over `[A-Z0-9]`; produces valid `Txid`
- [x] Unit tests first: valid round-trip, lowercase normalization, each rejection class, generator output validity

### Acceptance
- [x] All tests green; construction with any of: 24 chars, 26 chars, `-`, empty → `IllegalArgumentException`
- [x] Generated txids always pass `Txid` validation (property over 100 samples)

## S2 — EndToEndId value object ☑

### Work
- [x] `EndToEndId` record validating `^E[A-Za-z0-9]{31}$` (32 chars total); composition internals are PSP-owned — shape only
- [x] Unit tests first: valid 32-char, 31/33 chars rejected, missing `E` prefix rejected

### Acceptance
- [x] All tests green; PSP-style sample (`E904038…` 32 chars) accepted

## S3 — BpsRate + FeeBreakdown (fee math) ☐

### Work
- [ ] `BpsRate` int 0..10 000 validated
- [ ] `FeeBreakdown.of(amountCents, bps)`: `fee = amount × bps / 10 000` **floor**; `net = amount − fee`; records `amount`, `fee`, `net` as `Money`
- [ ] `feeReversalFor(refundCents, originalFeeCents)`: proportional floor
- [ ] Property tests (jqwik): for random amounts/rates — `fee + net == amount`; `reversal(refund1) + reversal(refund2) ≤ fee` when `refund1 + refund2 ≤ amount`; full-amount reversal ≤ original fee with equality iff divisible
- [ ] Zero-amount and zero-bps edges: fee 0, net = amount

### Acceptance
- [ ] Properties green over generated inputs; documented rounding rule matches coding-standards §3 (down, merchant-favorable, documented)

## S4 — Payment state machine ☐

### Work
- [ ] `Payment` aggregate: factory `create(txid, merchantId, amount, description, expiresAt, now)` → `PENDING` + `PaymentCreated`
- [ ] `confirm(endToEndId, FeeBreakdown, when)` — legal from `PENDING` and `EXPIRED`; from `EXPIRED` forces
      `lateConfirmation=true`; sets fee/net/endToEndId/`confirmedAt`; raises `PaymentConfirmed`
- [ ] `expire(when)` — legal from `PENDING`, guarded `when > expiresAt`; raises `PaymentExpired`
- [ ] `markFailed(reason)` — legal from `PENDING`; raises `PaymentFailed`
- [ ] `refund(refundAmount, feeReversal)` — legal from `CONFIRMED`/`PARTIALLY_REFUNDED`; validates
      `refundAmount ≤ remaining`; `refundAmount == remaining` → `REFUNDED` else `PARTIALLY_REFUNDED`; raises `RefundCreated`
- [ ] `domainEvents()` collector (drain semantics), `version` field carried for the seam
- [ ] Table-driven unit suite: every cell of spec §5 asserted (legal: state+version+event; illegal: exception type)

### Acceptance
- [ ] Every legal transition green; every illegal transition throws `InvalidTransitionException`
- [ ] `REFUNDED`/`FAILED` reject everything (terminal); second `confirm` on `CONFIRMED` throws (idempotent
      replay handling is the use case's job in E4)
- [ ] Resurrection covered: `EXPIRED → CONFIRMED` with `late=true` preserved in event payload

## S5 — Typed domain errors ☐

### Work
- [ ] `InvalidTransitionException` (from,to,txid context), `RefundExceedsRemainingException` extending a
      small `PaymentDomainException` base (HTTP mapping happens in E3, not here)
- [ ] Unit assertions on exception context fields

### Acceptance
- [ ] Exceptions carry enough context for the future 409 mapping (from, to, txid / remaining, requested)

## S6 — PaymentRepository port + in-memory fake ☐

### Work
- [ ] `PaymentRepository` (domain/port/out): `save(Payment)`, `findByTxid(Txid) → Optional<Payment>`,
      `updateIfVersionMatches(Payment, expectedVersion) → boolean`
- [ ] Contract documented on the interface: `false` = lost race, caller re-reads; adapter must never throw on lost races
- [ ] `InMemoryPaymentRepository` (test scope) implementing semantics including version-mismatch → `false`

### Acceptance
- [ ] Fake passes a contract test suite shared with the JPA adapter (S9 reuses the same assertions)

## S7 — V102 payments table migration ☐

### Work
- [ ] `modules/payments/src/main/resources/db/migration/payments/V102__create_payments_table.sql` per spec §8
      (uuid PK, txid varchar(25) unique, merchant_id, description, amount/status/version, expires_at,
      end_to_end_id, fee/net, late_confirmation, created_at/confirmed_at)
- [ ] design.md §5.1 gains the `description` row (docs synced in the same change set)

### Acceptance
- [ ] Migration runs clean on PostgreSQL 16 (proven by S9/S10 ITs); gap numbering preserved (V102, payments V1xx)

## S8 — PaymentEntity + mapper + PaymentJpaAdapter ☐

### Work
- [ ] `PaymentEntity` (@Entity, `@Version` on version column, table `payments.payments`) — adapter package only
- [ ] `PaymentMapper`: domain ↔ persistence, including raised-events preservation across load
- [ ] `PaymentJpaAdapter implements PaymentRepository`; `updateIfVersionMatches` relies on `@Version`
      (`OptimisticLockingFailureException` → `false`)
- [ ] Module gains `spring-boot-starter-data-jpa` (compile) — adapter packages only, per prompt decision 5

### Acceptance
- [ ] ArchUnit domain purity still green (no framework imports under `domain/`)
- [ ] Boundary script still green (prod-only scan unaffected)

## S9 — PaymentJpaAdapterIT against real PostgreSQL 16 ☐

### Work
- [ ] IT in payments module: Testcontainers `postgres:16-alpine`, Flyway pointed at
      `classpath:db/migration/payments`, JPA wired via a minimal test configuration
- [ ] Reuses the S6 contract suite: save → find round-trip, update with matching version → true + persisted
      state, stale version → false, confirm-transition persistence (fee/net/late/endToEndId columns)

### Acceptance
- [ ] IT green in CI (`mvn -B verify` picks `*IT` via Failsafe); no H2 anywhere

## S10 — Concurrent transition IT — exactly one winner ☐

### Work
- [ ] `PaymentConcurrentTransitionIT`: one persisted `PENDING` payment; 8 threads barrier-synced; each loads,
      attempts `confirm(...)`, calls `updateIfVersionMatches`; then exactly-one-winner assertions
- [ ] Loser path asserted: `false` returned, re-read shows winner's state, domain guard rejects a re-attempt
- [ ] Deterministic barrier (playbook §3) — no sleeps, no probabilistic stress in the PR gate

### Acceptance
- [ ] Exactly one thread returns `true` across runs; payment ends `CONFIRMED` with one event set
- [ ] Test named per playbook §3 (`concurrent_confirmations_with_version_guard_yield_exactly_one_winner`)

## S11 — Docs sync & closure ☐

### Work
- [ ] design.md §5.1 `description` row; epics.md E1 → ✅; `tasks/e1-acceptance-matrix.md` fully evidenced
- [ ] CHANGELOG Unreleased entry; lessons.md entry if the race IT or the adapter taught something non-obvious

### Acceptance
- [ ] Zero `pending` cells in the matrix; docs/tree drift list empty
