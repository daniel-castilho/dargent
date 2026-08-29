# Payment Domain & State Machine E1 — Backlog

## Epic E1 — Rich Payment Entity, Value Objects, Fee Math & the Optimistic-Lock Persistence Seam

**Priority:** P0
**All stories:** Must
**Companions:** `payment-domain-e1-spec.md` · `payment-domain-e1-implementation-sequence.md` · `ai-software-engineer-prompt-payment-domain-e1.md`

**Execution status:** opened 2026-08-28 after M0 closure (CI run #33217044326 green). Greenfield epic —
**S1–S11 ☑ CLOSED 2026-08-28.** Test-first process is mandatory for S1–S3 (prompt rule 1).

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

## S3 — BpsRate + FeeBreakdown (fee math) ☑

### Work
- [x] `BpsRate` int 0..10 000 validated
- [x] `FeeBreakdown.of(amountCents, bps)`: `fee = amount × bps / 10 000` **floor**; `net = amount − fee`; records `amount`, `fee`, `net` as `Money`
- [x] `feeReversalFor(refundCents, originalFeeCents)`: proportional floor — implemented as instance
      `feeReversalFor(refundCents)` using this breakdown's amount/fee (the formula also needs the
      original amount; see sequence-file deviation note)
- [x] Property tests (jqwik): for random amounts/rates — `fee + net == amount`; `reversal(refund1) + reversal(refund2) ≤ fee` when `refund1 + refund2 ≤ amount`; full-amount reversal ≤ original fee with equality iff divisible
- [x] Zero-amount and zero-bps edges: fee 0, net = amount (zero amount rejected upstream, `of` guards it)

### Acceptance
- [x] Properties green over generated inputs; documented rounding rule matches coding-standards §3 (down, merchant-favorable, documented)

## S4 — Payment state machine ☑

### Work
- [x] `Payment` aggregate: factory `create(txid, merchantId, amount, description, expiresAt, now)` → `PENDING` + `PaymentCreated`
- [x] `confirm(endToEndId, FeeBreakdown, when)` — legal from `PENDING` and `EXPIRED`; from `EXPIRED` forces
      `lateConfirmation=true`; sets fee/net/endToEndId/`confirmedAt`; raises `PaymentConfirmed`
- [x] `expire(when)` — legal from `PENDING`, guarded `when > expiresAt`; raises `PaymentExpired`
- [x] `markFailed(reason, when)` — legal from `PENDING`; raises `PaymentFailed` (deviation: gains a `when`
      param — see sequence-file deviation log; no `Instant.now()` inside the entity is non-negotiable)
- [x] `refund(refundAmount, feeReversal, when)` — legal from `CONFIRMED`/`PARTIALLY_REFUNDED`; validates
      `refundAmount ≤ remaining`; `refundAmount == remaining` → `REFUNDED` else `PARTIALLY_REFUNDED`; raises `RefundCreated`
- [x] `domainEvents()` collector (drain semantics), `version` field carried for the seam; `restore(...)`
      adapter-only factory (rebuild without events, preserves version)
- [x] Table-driven unit suite: every cell of spec §5 asserted (legal: state+version+event; illegal: exception type)

### Acceptance
- [x] Every legal transition green; every illegal transition throws `InvalidTransitionException`
- [x] `REFUNDED`/`FAILED` reject everything (terminal); second `confirm` on `CONFIRMED` throws (idempotent
      replay handling is the use case's job in E4)
- [x] Resurrection covered: `EXPIRED → CONFIRMED` with `late=true` preserved in event payload

## S5 — Typed domain errors ☑

### Work
- [x] `InvalidTransitionException` (from,to,txid context), `RefundExceedsRemainingException` extending a
      small `PaymentDomainException` base (HTTP mapping happens in E3, not here)
- [x] Unit assertions on exception context fields (asserted inline in the PaymentTest illegal cells)

### Acceptance
- [x] Exceptions carry enough context for the future 409 mapping (from, to, txid / remaining, requested)

## S6 — PaymentRepository port + in-memory fake ☑

### Work
- [x] `PaymentRepository` (domain/port/out): `save(Payment)`, `findByTxid(Txid) → Optional<Payment>`,
      `updateIfVersionMatches(Payment, expectedVersion) → boolean`
- [x] Contract documented on the interface: `false` = lost race, caller re-reads; adapter must never throw on lost races;
      `save` rejects duplicate txid with `DuplicatePaymentTxidException` (adapter-agnostic, txid-regeneration contract D4)
- [x] `InMemoryPaymentRepository` (test scope) implementing semantics including version-mismatch → `false` — snapshots
      on read/write via `restore` (no aliasing, faithful reload semantics)

### Acceptance
- [x] Fake passes a contract test suite shared with the JPA adapter (S9 reuses the same assertions)

## S7 — V102 payments table migration ☑

### Work
- [x] `modules/payments/src/main/resources/db/migration/payments/V102__create_payments_table.sql` per spec §8
      (uuid PK, txid varchar(25) unique, merchant_id, description, amount/status/version, expires_at,
      end_to_end_id, fee/net, late_confirmation, created_at/confirmed_at)
- [x] design.md §5.1 gains the `description` row (docs synced in the same change set)

### Acceptance
- [x] Migration runs clean on PostgreSQL 16 (proven by S9/S10 ITs); gap numbering preserved (V102, payments V1xx)

## S8 — PaymentEntity + mapper + PaymentJpaAdapter ☑

### Work
- [x] `PaymentEntity` (@Entity, `@Version` on version column, table `payments.payments`) — adapter package only
- [x] `PaymentMapper`: domain ↔ persistence, including effects of raised events across load
- [x] `PaymentJpaAdapter implements PaymentRepository`; `updateIfVersionMatches` is an explicit conditional
      UPDATE (`SET … version = :expected+1 WHERE txid = :txid AND version = :expected`, zero rows → `false`),
      reflecting the persisted version back on the aggregate via `Payment.markPersistedVersion`
- [x] Module gains `spring-boot-starter-data-jpa` (compile) + `spring-context` (compile, for the adapter's
      `@Repository`/`@Transactional`) — adapter packages only, per prompt decision 5
- [x] `apps/api` MigrationIT updated: `payments` schema now holds the `payments` table from V102 (E1)

### Acceptance
- [x] ArchUnit domain purity still green (no framework imports under `domain/`)
- [x] Boundary script still green (prod-only scan unaffected)

## S9 — PaymentJpaAdapterIT against real PostgreSQL 16 ☑

### Work
- [x] IT in payments module: Testcontainers `postgres:16-alpine`, Flyway pointed at
      `classpath:db/migration/payments`, JPA wired via a minimal test configuration
- [x] Reuses the S6 contract suite: save → find round-trip, update with matching version → true + persisted
      state, stale version → false, confirm-transition persistence (fee/net/late/endToEndId columns)

### Acceptance
- [x] IT green in CI (`mvn -B verify` picks `*IT` via Failsafe); no H2 anywhere

## S10 — Concurrent transition IT — exactly one winner ☑

### Work
- [x] `PaymentConcurrentTransitionIT`: one persisted `PENDING` payment; 8 threads barrier-synced; each loads,
      attempts `confirm(...)`, calls `updateIfVersionMatches`; then exactly-one-winner assertions
- [x] Loser path asserted: `false` returned, re-read shows winner's state, domain guard rejects a re-attempt
- [x] Deterministic barrier (playbook §3) — no sleeps, no probabilistic stress in the PR gate

### Acceptance
- [x] Exactly one thread returns `true` across runs; payment ends `CONFIRMED` with one event set
- [x] Test named per playbook §3 (`concurrent_confirmations_with_version_guard_yield_exactly_one_winner`)
      and stable across 5 consecutive local runs (08-28)

## S11 — Docs sync & closure ☑

### Work
- [x] design.md §5.1 `description` row; epics.md E1 → ✅; `tasks/e1-acceptance-matrix.md` fully evidenced
- [x] CHANGELOG Unreleased entry; lessons.md entry (#12: flush-catch vs conditional UPDATE — the race IT's lesson)

### Acceptance
- [x] Zero `pending` cells in the matrix; docs/tree drift list empty
