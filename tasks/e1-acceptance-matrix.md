# E1 Acceptance Matrix — Payment Domain & State Machine

Traceability: requirement → implementation → test → evidence (AGENTS.md §6; format stolen from the M0
matrix). An epic closes only when every row has evidence. `pending` = open.

| # | Requirement | Implementation | Test | Evidence |
|---|---|---|---|---|
| 1 | Transition table 100% covered (spec §5) | `Payment` aggregate state machine in `modules/payments/…/domain/model/Payment.java` | `PaymentTest` table-driven suite (21 rows ↔ spec §5.2) | Local: `PaymentTest` 21 ✅ under `mvn -B verify -pl modules/payments`; CI: **run — see S11 evidence** |
| 2 | Resurrection with audit flag (D6, spec §5) | `confirm()` on `EXPIRED` forces `lateConfirmation=true`; `PaymentConfirmed` carries `late` | `PaymentTest` resurrection rows; `resurrection_late_flag_round_trips` in `PaymentRepositoryContractSuite` | Local: both green ✅ (JPA adapter IT includes the round-trip on real PG) |
| 3 | Fee invariants (spec §7) | `BpsRate` + `FeeBreakdown` in `domain/model` | `FeeBreakdownTest` — 7 unit edges + 6 jqwik properties (§7.1–7.5) | Local: `FeeBreakdownTest` (13) ✅ under `mvn -B verify -pl modules/payments` |
| 4 | VOs reject invalid shapes (spec §4, D4) | `Txid` (`^[A-Z0-9]{25}$`), `EndToEndId` (`^E[A-Za-z0-9]{31}$`), `BpsRate` (`[0,10_000]`) | `TxidTest` (8), `EndToEndIdTest` (6), `BpsRateTest` (3) | Local: all green ✅ |
| 5 | Port lost-race semantics (spec §6) | `PaymentRepository` port + `DuplicatePaymentTxidException`; lost race = `updateIfVersionMatches` → `false`, adapter never throws | `PaymentRepositoryContractSuite` (8 tests) run on `InMemoryPaymentRepositoryContractTest` AND `PaymentJpaAdapterIT` | Local: both 8/8 green ✅; JPA adapter on real PostgreSQL 16 |
| 6 | V102 table correct on real PG (spec §8) | `V102__create_payments_table.sql`; migrate runs via module-owned Flyway location | `PaymentJpaAdapterIT` (Testcontainers `postgres:16-alpine`) | Local: IT green ✅ (Flyway applies V101+V102, Hibernate `ddl-auto=validate`) |
| 7 | Exactly one winner under race (spec §9) | Conditional-UPDATE adapter + `PaymentConcurrentTransitionIT` | 8 threads, `CyclicBarrier(8)`, exactly-one-winner assertions; loser re-read + domain-guard re-attempt | Local: 5 consecutive green runs ✅ (08-28) |
| 8 | Gates untouched | Adapter/entity confined to `adapter/out/persistence`; domain framework-free | `PaymentsArchitectureTest` + `scripts/check-boundaries.sh` | Local: ArchUnit 3 ✅; `check-boundaries: OK` ✅ |
| 9 | Docs synced | design.md §5.1 gains `description` row; `docs/epics.md` E1 → ✅; CHANGELOG; lessons | review | ✅ this matrix row; CHANGELOG Unreleased entry |

## Declared deviations (residual, with owner)

| Deviation | Why | Owner | Target |
|---|---|---|---|
| `PaymentJpaAdapter` uses explicit conditional UPDATE, not `@Version` flush-catch (deviation log S8) | failed flush marks tx rollback-only → loser commit throws; conditional UPDATE is the mandated arbitration (AGENTS.md §3.2) | — | permanent (documented) |
| Entity `id` DB-generated UUID instead of app-generated UUIDv7 (D3) | id is not part of the balance aggregate; `@GeneratedValue` conflicts with app-assigned ids | — | out of E1 scope |
| Quality gates not exercised here (SpotBugs/OWASP/JaCoCo/Trivy/CodeQL/k6) | planned at M4 per design.md §11 | — | M4 |