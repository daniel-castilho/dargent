# AI Software Engineer Prompt — Payment Domain & State Machine E1

## Epic E1 — Rich Payment Entity, Value Objects, Fee Math & the Optimistic-Lock Persistence Seam

**Status:** Ready for implementation — greenfield on top of a closed M0 (CI green, all gates proven)
**Priority:** P0 — every money path in the system flows through this epic's state machine
**Target:** A pure, framework-free payment domain that makes invalid states unrepresentable, a persistence seam
where the database arbitrates races, and the concurrent race IT proving "exactly one winner"
**Package:** `io.dargent.payments` (plus one contract-sync touch on `apps/api` Flyway wiring — nothing else)

You are the Software Engineer owning the **Payment Domain & State Machine (E1)** epic for the Dargent API.
This is the first epic written **test-first** (red → green → refactor), and it sets the quality bar every
later epic inherits. Correctness of the money state machine takes absolute priority over speed, abstraction
elegance and feature scope. You do **not** implement use cases, REST, webhooks, idempotency keys, PSP clients,
outbox or messaging — those are E3+.

---

## Sources of truth — read in this order

1. `AGENTS.md`
2. `pom.xml` and `.github/workflows/ci.yml` (green baseline — keep it green)
3. `docs/design.md` (§4.1 state machine, §4.2 PIX specifics, §5.1 data model, §10 testing)
4. `docs/coding-standards.md` (§2 domain modeling, §3 money — this epic is their living proof)
5. `docs/testing-playbook.md` (§2 taxonomy, §3 infrastructure rules, §4 scenarios 12–13)
6. `docs/data-model-decisions.md` (D4 txid, D5 money/bps, D6 conditional UPDATE)
7. `docs/lessons.md` (#1 read-modify-write races, #2 retry outside transactional seam)
8. `tasks/payment-domain-e1-spec.md` — the exact contracts
9. `tasks/payment-domain-e1-backlog.md` — stories and acceptance
10. `tasks/payment-domain-e1-implementation-sequence.md` — **your execution script**
11. Current production code and colocated `*Test` / `*IT` classes

If documentation disagrees with executable configuration, stop, report the mismatch and resolve it in the same
change set.

---

## Goal

Deliver the payment domain core such that:

- every legal state transition is an intention-revealing method on the `Payment` entity, and every illegal
  one is **impossible** (guarded by the entity, typed exception, zero setters);
- the transition table (including refund transitions and `EXPIRED`-non-terminal resurrection) is covered by
  exhaustive table-driven unit tests written before the implementation;
- money math (fee in basis points, proportional reversal) is property-tested: `fee + net == amount`, always;
- the persistence seam is an optimistic version-guarded update — the database arbitrates races — proven by a
  concurrent IT where N threads race and **exactly one wins**.

The epic closes these current gaps:

- `PaymentStatus` exists as a seed enum with no entity enforcing it;
- there is no `Payment` aggregate, no `Txid`/`EndToEndId` VOs, no fee computation anywhere;
- the payments module has no persistence (no table, no adapter, no port);
- no test in the repository proves race semantics yet.

---

## Locked technical decisions

Re-litigating any of these is out of scope; they are ADR-backed (design.md §2, §15).

1. **Rich entity, zero setters** — lifecycle guarded inside `Payment` (coding-standards §2). Domain package
   imports no framework (ArchUnit enforces; the M0 gate-proof fixture is the precedent).
2. **`EXPIRED` is not terminal** (D6): a late confirmation resurrects (`EXPIRED → CONFIRMED` with
   `late=true` forced). `REFUNDED` and `FAILED` are terminal.
3. **Fee computed in payments** (D7): basis points, integer math, rounding **down**; event/consumer-facing
   breakdown always travels with the event (`amount`, `fee`, `net`). `fee + net == amount` is a property,
   not a hope.
4. **`expiresAt` comes from the PSP** — the entity receives it at creation; it is never computed locally.
5. **JPA at the edge only** (D14): `PaymentEntity` + mapper in `adapter/out/persistence`; the domain `Payment`
   and the persistence model are **separate classes**. JPA/Spring dependencies enter the payments module in
   this epic, but only adapter packages may touch them.
6. **Optimistic version guard at the aggregate level** is the E1 seam (`updateIfVersionMatches` returning
   `boolean`); targeted bulk conditional UPDATEs (`WHERE status IN (...)`, `SKIP LOCKED`) arrive with their
   epics (scheduler E5, relay E6, refund lock E8). Lost race (`false`) is a first-class result the caller
   handles — never an exception thrown from the adapter.
7. **Refund state transitions are entity-owned now; refund orchestration is E8.** The `refund(amount)`
   transition validates against the payment's remaining amount internally; balance (`ledger`) checks are
   E8 and stay out of this epic.
8. **Domain events are entity-raised records** (`PaymentCreated`, `PaymentConfirmed`, `PaymentExpired`,
   `PaymentFailed`, `RefundCreated`) collected in a `domainEvents()` list. Their mapping to the
   `EventEnvelope`/outbox is E6 — no serialization here.
9. **Testcontainers PostgreSQL 16 for the ITs — never H2.** The payments module's IT points Flyway at its
   own `classpath:db/migration/payments` (single-source DDL, module-owned migrations).
10. **Dependency additions are locked** (spec §4): `spring-boot-starter-data-jpa` (adapter scope) plus
    Testcontainers test deps in the payments module. Nothing else.

---

## Non-negotiable engineering rules

1. **TDD is the process, not the garnish:** for every S1/S2 story, the failing test exists before the
   implementation that satisfies it. A green test suite with no prior red is a process violation.
2. Work in small, reviewable vertical commits (`feat(payments): txid value object`, …) — never one big drop.
3. Test names read as specifications
   (`late_confirmation_of_expired_payment_resurrects_it_with_audit_flag`); table-driven where a table exists.
4. Every lost-race code path is exercised by a test (unit fake + concurrent IT).
5. No dependency beyond spec §4 without explicit approval; the spec is updated in the same change if approved.
6. Red baseline stops work; a green-baseline regression on `main` must be diagnosed before new commits.
7. After each step: update backlog checkboxes, note deviations in the sequence file, keep `docs/` truthful.
8. Sources are 100% English. No secrets, no logs of future sensitive payloads.

---

## Required contracts (the E1 definition of shape)

- **State machine:** exact table in spec §5 — `PENDING→CONFIRMED` (accepts from `EXPIRED` w/ `late=true`),
  `PENDING→EXPIRED` (guard `now > expiresAt`), `PENDING→FAILED`, `CONFIRMED/PARTIALLY_REFUNDED→
  PARTIALLY_REFUNDED/REFUNDED` via `refund(amount)` with remaining-amount validation. Terminal:
  `REFUNDED`, `FAILED` only.
- **Value objects:** `Txid` (`^[A-Z0-9]{25}$`), `EndToEndId` (`^E[A-Za-z0-9]{31}$`, 32 total),
  `BpsRate` (0..10 000), `FeeBreakdown` (`amount`, `fee`, `net` + proportional `feeReversal`).
- **Port:** `PaymentRepository` — `save`, `findByTxid`, `updateIfVersionMatches(payment, expectedVersion) → boolean`.
- **Persistence:** V102 migration creating `payments.payments` exactly per spec §8 (including the
  `description` column — and the one-line design.md §5.1 sync in the same change set).
- **Proofs:** transition-table unit suite; fee property tests; `concurrent_transitions_with_version_guard`
  IT with exactly one winner; Flyway-backed `PaymentJpaAdapterIT` against real PostgreSQL 16.

## Scope exclusions (hard boundaries)

- No use-case layer services, no REST controllers, no DTOs, no error-envelope wiring (E3/E4).
- No idempotency keys, no webhook intake, no HMAC (E3/E4).
- No BR Code/EMV generation, no PSP client, no psp-simulator API (E2/E3).
- No outbox table, relay, SNS/SQS adapters, envelope serialization (E6).
- No ledger, balances, settlement (E7); no refund use case/lock orchestration/balance checks (E8).
- No schedulers, no expiration scanning (E5) — the entity exposes `expire(now)`; who calls it is E5.
- No metrics/logs changes (E11); no CI workflow changes (the existing pipeline already runs `mvn -B verify`).

## Definition of Done (epic)

### Domain
- [ ] Transition table 100% covered: every legal transition asserts new state + version bump + raised event;
      every illegal one asserts `InvalidTransitionException` (table-driven)
- [ ] Resurrection path covered: `EXPIRED → CONFIRMED` sets `late=true`, preserves audit fields
- [ ] Fee math property tests green: rounding down, `fee+net==amount`, full-refund reversal ≤ original fee
- [ ] VOs reject invalid shapes at construction; generators produce valid values

### Persistence seam
- [ ] `PaymentRepository` port + in-memory fake; adapter implements the port behind the version guard
- [ ] V102 migration creates the table; `PaymentJpaAdapterIT` green against Testcontainers PostgreSQL 16
- [ ] Concurrent IT: N threads, one winner, losers observe `false` and re-read consistent state

### Discipline & closure
- [ ] ArchUnit + boundary script still green (domain stayed pure)
- [ ] design.md §5.1 synced (`description` column); epics.md E1 flipped; `tasks/e1-acceptance-matrix.md`
      fully evidenced; CHANGELOG updated
- [ ] Lessons updated with anything the race IT taught us
