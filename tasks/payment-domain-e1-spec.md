# Payment Domain & State Machine E1 — Technical Specification

## Epic E1 — Rich Payment Entity, Value Objects, Fee Math & the Optimistic-Lock Persistence Seam

**Priority:** P0
**Companions:** `payment-domain-e1-backlog.md` · `payment-domain-e1-implementation-sequence.md` · `ai-software-engineer-prompt-payment-domain-e1.md`
**Baseline:** M0 closed (CI run #33217044326 green). Greenfield epic inside `modules/payments`.

---

## 1. Purpose

Give Dargent its money brain: a payment aggregate whose state machine is the single authority on what may
happen to a payment, value objects that make invalid data unrepresentable, fee arithmetic with proven
invariants, and a persistence seam where the database — not optimism — arbitrates concurrent transitions.
Every guarantee the system advertises (design.md §1.1) has its root here.

---

## 2. Scope

### In scope
- `Payment` aggregate with the full forward-only transition table (including refund transitions and
  `EXPIRED`-non-terminal resurrection) and entity-raised domain events;
- Value objects: `Txid` (+ generator), `EndToEndId`, `BpsRate`, `FeeBreakdown` (fee math + reversal);
- Typed domain exceptions with future-HTTP-mapping context;
- `PaymentRepository` port with lost-race semantics + in-memory fake + shared contract test suite;
- V102 `payments` table migration (module-owned) + JPA entity/mapper/adapter behind a version guard;
- ITs on real PostgreSQL 16: adapter contract suite + concurrent exactly-one-winner race proof;
- Docs sync for the `description` column.

### Out of scope
- Use cases, REST, DTOs, error envelope (E3/E4); webhook intake/HMAC (E4); BR Code/PSP client (E2/E3);
- Outbox/relay/envelope serialization (E6); ledger/balances/settlement (E7);
- Refund orchestration (pessimistic lock, balance checks, ledger entries) — the entity transition exists,
  the use case is E8;
- Expiration scheduling, targeted bulk `UPDATE ... WHERE status IN` statements (E5), relay `SKIP LOCKED` (E6);
- Metrics, logs, CI changes (E11/E13).

---

## 3. Architectural constraints

### 3.1 Package shape (fixed)

```
io.dargent.payments/
├── domain/
│   ├── model/          Payment, PaymentStatus (exists), domain events
│   ├── model/          Txid, EndToEndId, BpsRate, FeeBreakdown (value objects)
│   ├── exception/      PaymentDomainException base + InvalidTransition + RefundExceedsRemaining
│   └── port/out/       PaymentRepository, TxidGenerator
├── application/        (EMPTY in E1 — first use case arrives in E3)
└── adapter/
    └── out/persistence/  PaymentEntity, PaymentMapper, PaymentJpaAdapter
```

Test scope: `architecture/` (exists), `domain/**` unit suites, `persistence/` contract suite + fake,
`race/` concurrent IT.

### 3.2 Dependency direction (enforced by existing gates)

`domain/` imports nothing framework. `adapter/out/persistence` may import Spring/JPA. ArchUnit + boundary
script must stay green through every step — a red gate is a bug, never a rule change.

### 3.3 Dependency additions (approved set — nothing else)

| Artifact | Scope | Justification |
|---|---|---|
| `org.springframework.boot:spring-boot-starter-data-jpa` | payments, compile | JPA adapter (D14); Boot-managed Hibernate |
| `org.testcontainers:testcontainers-junit-jupiter` + `testcontainers-postgresql` | payments, test | Real-PostgreSQL ITs (BOM already imported at parent) |
| `com.tngtech.archunit:archunit-junit5` | already present | — |
| `net.jqwik:jqwik` | payments, test | Fee-math property tests (playbook §4 scenario 22 uses jqwik from M2; adopted here first — engine coexists with JUnit 6 via junit-platform) |

`jqwik` is the only addition beyond the prompt's locked list — it is required by the property-testing
requirement of S3 and already named in design.md §10.2. If disapproved, S3 falls back to randomized
AssertJ loops with fixed seeds (weaker, acceptable, documented).

---

## 4. Decision map (traceability)

| Spec element | Source |
|---|---|
| Transition table | design.md §4.1 (D6, D11) |
| Fee in bps, breakdown travels with events | D7, coding-standards §3 |
| txid 25 alnum, app-generated | D4 |
| JPA at the edge, separate domain entity | D14 |
| Optimistic version guard (aggregate) | D6, lesson #1; bulk variants explicitly deferred (E5/E6/E8) |
| Real PostgreSQL in ITs, module-owned Flyway locations | testing-playbook §1/§3, M0 MigrationIT precedent |

---

## 5. The state machine (exact contract)

### 5.1 States (existing `PaymentStatus` — unchanged)

`PENDING, CONFIRMED, PARTIALLY_REFUNDED, REFUNDED, EXPIRED, FAILED` — terminal: `REFUNDED`, `FAILED` only.
`isTerminal()` already encodes this.

### 5.2 Transition table (entity methods; every cell has a unit test)

| Method | Legal from | To | Guards (preconditions) | Effects | Raised event |
|---|---|---|---|---|---|
| `create(txid, merchantId, amount, description, expiresAt, now)` | — (birth) | `PENDING` | amount > 0; expiresAt > now (guard: else `IllegalArgumentException`); VOs already valid by construction | version=0, timestamps set | `PaymentCreated` |
| `confirm(endToEndId, FeeBreakdown, when)` | `PENDING` | `CONFIRMED` | `when` before terminal; breakdown.amount == payment.amount | fee/net/endToEndId/confirmedAt set; `late=false` | `PaymentConfirmed(late=false)` |
| `confirm(endToEndId, FeeBreakdown, when)` | `EXPIRED` | `CONFIRMED` | same | **`late=true` forced**; same fields | `PaymentConfirmed(late=true)` |
| `confirm(...)` | `CONFIRMED` / `PARTIALLY_REFUNDED` / `REFUNDED` / `FAILED` | — | — | **throws** `InvalidTransitionException` (replay-idempotency is E4's concern) | — |
| `expire(when)` | `PENDING` | `EXPIRED` | `when > expiresAt` (else throw) | — | `PaymentExpired` |
| `expire(when)` | any other | — | — | **throws** | — |
| `markFailed(reason)` | `PENDING` | `FAILED` | — | reason kept in event only | `PaymentFailed(reason)` |
| `markFailed(reason)` | any other | — | — | **throws** | — |
| `refund(refundAmount, feeReversal, when)` | `CONFIRMED` / `PARTIALLY_REFUNDED` | `PARTIALLY_REFUNDED` if `refundAmount < remaining`; `REFUNDED` if `==` | `refundAmount ≤ remaining` (else `RefundExceedsRemainingException`); `refundAmount > 0` | remaining decreases; reversal amounts recorded on event | `RefundCreated(amount, feeReversal, netReversal)` |
| `refund(...)` | `PENDING` / `EXPIRED` / `REFUNDED` / `FAILED` | — | — | **throws** | — |

`remaining` = `amount − Σ refunded` (tracked on the aggregate; the ledger's balance check is E8 and lives
elsewhere). Every successful transition bumps `version` by 1. Events are collected in raised order;
`domainEvents()` drains (idempotent empty on second call). **No `Instant.now()` inside the entity** — all
time arrives as parameters.

### 5.3 Domain events (records in `domain/model`)

| Record | Fields |
|---|---|
| `PaymentCreated` | txid, merchantId, amount, description, expiresAt, occurredAt |
| `PaymentConfirmed` | txid, endToEndId, amount, fee, net, late, occurredAt |
| `PaymentExpired` | txid, occurredAt |
| `PaymentFailed` | txid, reason, occurredAt |
| `RefundCreated` | txid, refundAmount, feeReversal, netReversal, occurredAt |

All carry `txid` as aggregate id; `occurredAt` = the transition's `when` parameter. Envelope mapping
(`payment.*` type strings, JSON) is E6 — these records stay serialization-free.

---

## 6. Repository port (exact contract)

```java
public interface PaymentRepository {
    void save(Payment payment);                       // insert; duplicate txid → adapter throws DataIntegrityViolation-style domain-agnostic exception (E3 retries txid generation)
    Optional<Payment> findByTxid(Txid txid);
    boolean updateIfVersionMatches(Payment payment, int expectedVersion);
    // true  = row updated (version consumed, now expectedVersion+1 on the passed aggregate)
    // false = LOST RACE — the row changed underneath; the aggregate passed in is now stale.
    //         Callers MUST re-read and re-decide. The adapter NEVER throws on a lost race.
}
```

- The in-memory fake and the JPA adapter both extend one abstract **contract test suite** (assertions on:
  save/find round-trip including events preservation; matching version → `true` + persisted effects;
  stale version → `false`; second update with refreshed version → `true`).
- The JPA adapter implements the guard via `@Version`; `OptimisticLockingFailureException` → `false`
  (catch at the adapter seam, unit-tested). No generic `RuntimeException` may escape a lost race.

---

## 7. Fee math (exact formulas)

Let `amount`, `fee`, `net`, `refund`, `reversal` be integer cents; `bps ∈ [0, 10 000]`.

```
fee      = floor(amount × bps / 10 000)
net      = amount − fee
reversal = floor(originalFee × refund / originalAmount)
```

Invariants (property-tested, jqwik):
1. `fee + net == amount` for all inputs.
2. `0 ≤ fee ≤ amount`; `0 ≤ reversal ≤ originalFee`.
3. With refunds `r₁ + r₂ ≤ amount`: `reversal(r₁) + reversal(r₂) ≤ originalFee` (floor residue stays with
   the platform — merchant-favorable, documented).
4. `reversal(amount) == originalFee` iff `originalFee × amount` divisible by `amount` (i.e., the common
   case where the division is exact); otherwise `≤` per property 3 — the test documents which.
5. `bps == 0 → fee == 0, net == amount`; `amount == 0` is rejected upstream (amount > 0 at creation).

Rounding direction is **down** (merchant-favorable on net) — coding-standards §3; the javadoc repeats it.

---

## 8. V102 — `payments` table (exact DDL intent)

`modules/payments/src/main/resources/db/migration/payments/V102__create_payments_table.sql`

- `id uuid PRIMARY KEY` (UUIDv7 app-generated)
- `txid varchar(25) NOT NULL` + `UNIQUE`
- `merchant_id uuid NOT NULL`
- `description varchar(140)` (nullable) — **added by this epic; design.md §5.1 gains the row in the same change set**
- `amount_cents bigint NOT NULL CHECK (amount_cents > 0)`
- `status varchar(32) NOT NULL` (enum name as text; CHECK constraint on the six names)
- `version int NOT NULL DEFAULT 0` (`@Version` column)
- `expires_at timestamptz NOT NULL`
- `end_to_end_id varchar(32)` (nullable until confirmation)
- `fee_cents bigint`, `net_cents bigint` (nullable until confirmation)
- `late_confirmation boolean NOT NULL DEFAULT false`
- `refunded_cents bigint NOT NULL DEFAULT 0` (aggregate-tracked remaining; ledger remains the truth in E7+)
- `created_at timestamptz NOT NULL`, `confirmed_at timestamptz`
- No other indexes in E1 (partial expiration index arrives with E5; listing index with E3's listing story)

---

## 9. Concurrent race IT (the epic's headline proof)

`PaymentConcurrentTransitionIT` — `race/` package, Failsafe `*IT`:

1. Seed one `PENDING` payment (adapter `save`), expected version 0.
2. `ExecutorService` with **8 threads** + `CyclicBarrier(8)`; each thread: load → `confirm(fixedEndToEndId,
   breakdown, fixedWhen)` → `updateIfVersionMatches(payment, loadedVersion)`; collect booleans.
3. Assertions: **exactly one** `true`; winner's persisted state is `CONFIRMED` with the breakdown columns;
   every loser `false`; a loser's re-read shows the winner's state and version; a loser re-attempting
   `confirm` on the fresh load hits the domain guard (`InvalidTransitionException`) — the full lost-race
   contract in one test.
4. Fixed inputs everywhere (same endToEndId/when) — the ONLY nondeterminism is scheduling, which is the
   point. No sleeps; stability proven by 5 consecutive green runs (sequence Step 5).
5. Name: `concurrent_confirmations_with_version_guard_yield_exactly_one_winner`.

---

## 10. Verification matrix (maps to `tasks/e1-acceptance-matrix.md`)

| # | Criterion | Proven by |
|---|---|---|
| 1 | Transition table 100% covered | `PaymentTest` table-driven suite (rows ↔ §5.2) |
| 2 | Resurrection with audit flag | `PaymentTest` resurrection rows + event payload assert |
| 3 | Fee invariants | `FeeBreakdownTest` jqwik properties (§7.1–7.5) |
| 4 | VOs reject invalid shapes | `TxidTest`, `EndToEndIdTest`, `BpsRateTest` |
| 5 | Port lost-race semantics | Contract suite green on fake AND JPA adapter |
| 6 | V102 table correct on real PG | `PaymentJpaAdapterIT` (Testcontainers 16, module Flyway location) |
| 7 | Exactly one winner under race | `PaymentConcurrentTransitionIT` ×5 stable |
| 8 | Gates untouched | ArchUnit + boundary script green in CI |
| 9 | Docs synced | design.md §5.1 diff + epics.md + matrix + CHANGELOG |

---

## 11. Risks & troubleshooting

| Risk | Likelihood | Mitigation |
|---|---|---|
| Race IT flaky | Medium | Barrier correctness + adapter contract first (Step 3 before Step 5); failure playbook in the sequence file; never quarantine |
| JPA leaks into domain | Medium (first JPA introduction) | ArchUnit domain purity + boundary script after every structural commit |
| jqwik/JUnit 6 platform friction | Low | jqwik on junit-platform coexists with Jupiter; if the engine fight is real, the fallback in §3.3 applies (documented) |
| `@Version` semantics mismatch with port contract (e.g., version echoed wrong after update) | Medium | Contract suite asserts version progression explicitly (S6/S9) |
| Float temptation in fee refactorings | Low | Properties make any float path fail loudly; coding-standards §3 review checklist |

## 12. Closure checklist (epic DoD)

- [ ] §10 matrix fully evidenced (zero pending) in `tasks/e1-acceptance-matrix.md`
- [ ] CI green on `main` with the new unit + IT layers
- [ ] Domain purity gates green; dependency set == §3.3 (jqwik disposition documented if fallback)
- [ ] Docs synced (design.md §5.1 `description`, epics.md ✅, CHANGELOG, lessons if any)
- [ ] No scope bleed: `application/` still empty; no REST/outbox/ledger artifacts
