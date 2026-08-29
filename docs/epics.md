# Epics

Progress map of feature epics. Each epic has its spec + backlog + acceptance matrix in `tasks/`. An epic
closes when its milestone meets the Definition of Done (AGENTS.md §6) and its matrix has zero `pending`.

| Epic | Title | Status | Spec/backlog/matrix | Notes |
|---|---|---|---|---|
| M0 | Skeleton (build, CI, schemas, topology) | ✅ 2026-08-28 | `tasks/foundations-m0-*` | baseline CI green; quality gates deferred to M4 |
| **E1** | **Payment domain & state machine** | **✅ 2026-08-28** | `tasks/payment-domain-e1-*` | `Payment` aggregate, VOs, events, repository port + fake, V102, JPA adapter, exactly-one-winner race proof on real PG |
| E2 | PSP webhook intake & reconciliation | ⏳ pending | — | needs the failed/expired transition paths this epic built |
| E3 | Payment API (acceptance, idempotency) | ⏳ pending | — | builds on `PaymentRepository` port + txid generator |
| E4 | Outbox & event relay to SNS/SQS | ⏳ pending | — | events now surface from the aggregate (`PaymentEvent`) |
| E5 | Expiration scheduler | ⏳ pending | — | partial expiration index arrives with it (design.md §5.1) |
| E6 | PSP simulator conformance | ⏳ pending | — | — |
| E7 | Ledger & settlement | ⏳ pending | — | `refunded_cents` here is aggregate-tracked; ledger becomes truth then |
| E8 | Refund with `SELECT … FOR UPDATE` | ⏳ pending | — | refund lock story rides on E1's `refund()` transition |
| M4 | Quality gates (SpotBugs, OWASP, JaCoCo, Trivy, CodeQL, k6) | ⏳ pending | — | design.md §11 |

> Conventions: `✅` = epic DoD met, matrix zero `pending`; `⏳` = not started / mid-flight.