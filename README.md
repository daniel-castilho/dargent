# Dargent — PIX Payment Processing & Transaction System

> *d'argent* (French) — "of silver, of money", from Latin *argentum*.
> The system is where the money lives.

**A payment infrastructure backend with an architecture analogous to platforms like Stripe/Razorpay — built for the Brazilian PIX rail.**
Modular monolith on **Java 25 + Spring Boot 4.1**, engineered from day one to be extracted into microservices.

`Java 25` · `Spring Boot 4.1` · `PostgreSQL 16` · `SNS/SQS FIFO (LocalStack)` · `NGINX blue-green` · `MIT`

---

## What is Dargent?

Dargent implements the **complete payment lifecycle**: create → process → verify → webhook → success/failure → refund,
using **PIX with dynamic QR codes** mediated by a simulated PSP. The value is not the CRUD — it is the guarantees
(the *contracts* below are what the project is engineered to prove; each becomes a measured, CI-proven property
as its milestone closes — see *Current state* and the per-milestone acceptance matrices in `tasks/`):

| Guarantee | Mechanism |
|---|---|
| No payment is ever charged twice | Idempotency keys (request-level), webhook dedupe (`endToEndId` + type), consumer dedupe (`eventId`) |
| No confirmed payment is lost — even without a webhook | Signed webhook intake + **future reconciliation job (E5, not started)** against the PSP |
| Every cent is traceable and balanced | Append-only **double-entry ledger** + daily balance proof + property tests |
| Invalid states are impossible | State machine guarded by the entity **and** imposed by conditional `UPDATE`s (the database arbitrates races) |
| No downtime deploys on bare metal | NGINX **blue-green with canary**, instant rollback, shutdown-under-load gate in CI |
| Quality is auditable | Acceptance matrix per milestone, security gates in CI, executable documentation as tests |

## Architecture

```
                     ┌────────────────────────── on-premises host ─────────────────────────┐
                     │                                                                      │
 merchant ──HTTP──▶ NGINX :8080 ──▶ api-blue :8081  ┐  same JVM, modules:                  │
 payer app ──▶   (canary, DNS       api-green :8082 ┘   [ payments | ledger | notifications]│
                    re-resolution)                       │        ▲                        │
                                                         │ outbox │ events                 │
                                                         ▼        │ (SNS → SQS FIFO)       │
                                              ┌─────────────────────────┐                       │
                                              │ LocalStack :4566        │                       │
                                              │  payment-events.fifo    │                       │
                                              └─────────────────────────┘                       │
                                                         ▲                                      │
                                      HTTP (cob/webhook) │                                      │
                                              psp-simulator :8090 ───────────────────────────────┘
                                              (merchant-side PSP + payer bank + chaos knobs)
                                                         │
                                              PostgreSQL :5432  ◀── source of truth
                                              (schemas: payments | ledger | notifications)
```

The topology **is** microservices — it just runs in one JVM. Data ownership is separate per module, all cross-module
communication flows through the outbox → SNS → SQS, and each consumer has its own queue and DLQ. Extracting the
`ledger` is an infrastructure task, not a refactor.

## Modules

| Module | Responsibility |
|---|---|
| `modules/payments` | Payment lifecycle, idempotency, outbox, webhook intake, reconciliation, expiration, refunds, BR Code |
| `modules/ledger` | Append-only double-entry journal, balance projection, balance proof, D+1 settlement |
| `modules/notifications` | Event consumer, notification records (phase 2) |
| `modules/shared` | Minimal cross-cutting only: `Money`, event envelope, error contract, JSON serialization |
| `apps/api` | Boot application wiring the modules: REST, security, schedulers, messaging adapters |
| `apps/psp-simulator` | **Separate app** — the fake Stripe: merchant-side PSP + payer bank + configurable chaos |

## The money flow (TARGET STATE — narrates what will exist at M3/M5)

```
POST /v1/payments (Idempotency-Key) → PENDING + dynamic QR (BR Code, EMV + CRC16)
payer pays the QR at the simulator's "bank" → PSP fires signed webhook (HMAC + timestamp)
webhook validated → dedupe → conditional UPDATE → CONFIRMED (fee computed in bps)
outbox → SNS → SQS → ledger journals DR clearing / CR pending+fees · notifications notified
webhook never arrived? → reconciler (E5, future) asks the PSP and confirms on its own
QR expired but paid late? → resurrection (E5, future) with audit trail
POST /v1/payments/{txid}/refunds → partial/total, fee returned proportionally, ledger drains balance
```

## Documentation

| Document | Purpose |
|---|---|
| [`docs/design.md`](docs/design.md) | **Official design document** v1.0.2 (EN, canonical) · [`design-ptbr.md`](docs/design-ptbr.md) is the archived approval snapshot |
| [`docs/coding-standards.md`](docs/coding-standards.md) | How code must be written here |
| [`docs/data-model-decisions.md`](docs/data-model-decisions.md) | Recorded data-model decisions with alternatives |
| [`docs/lessons.md`](docs/lessons.md) | Hard-won lessons and golden rules |
| [`docs/testing-playbook.md`](docs/testing-playbook.md) | Test pyramid, scenario catalog, regression smoke |
| [`docs/observability.md`](docs/observability.md) | Logs, metrics, health model |
| [`docs/slos.md`](docs/slos.md) | Objectives, indicators and error budgets |
| [`docs/load-test-baseline.md`](docs/load-test-baseline.md) | k6 budgets-as-code, consultative baselines |
| [`docs/release-runbook.md`](docs/release-runbook.md) | Release, deploy, rollback, backup/restore drills |
| [`docs/twelve-factor.md`](docs/twelve-factor.md) | Twelve-factor compliance, factor by factor |
| [`AGENTS.md`](AGENTS.md) | Rules for AI agents and human contributors |

## Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 25 (LTS), virtual threads friendly |
| Framework | Spring Boot 4.1.x (Framework 7, Jackson 3, JUnit 6) |
| Build | Maven multi-module, split by bounded context |
| Persistence | PostgreSQL 16 · Flyway (forward-only, expand/contract) · JPA/Hibernate (payments) · `JdbcClient` (ledger) |
| Messaging | SNS/SQS FIFO on LocalStack via AWS SDK v2, behind our own channel adapters |
| Auth | Stripe-style API keys (`psp_test_…`, SHA-256 at rest) + HMAC-SHA256 webhooks with anti-replay |
| Observability | Boot 4 structured JSON logs, Micrometer + Prometheus |
| Tests | JUnit 6, Testcontainers 2.0, WireMock, Awaitility, jqwik, ArchUnit |
| Runtime | Docker Compose, NGINX blue-green with canary — no k8s |

## Getting started

Prerequisites: JDK 25 (Temurin), Docker with Compose, GNU make (optional).

> **⚠️ Honesty note:** `POST /v1/payments` + `GET /v1/payments` + `GET /v1/payments/{txid}` are **live (E3)**. The webhook → `CONFIRMED` leg lands with **E4 (complete as of E3R)** — `POST /webhooks/psp` is **live** with fail-closed HMAC, anti-replay, dedupe, and conditional confirmation. All scenario proofs (6, 7, 8, 10, ignored×3, full-loop) run in CI (runs #24 #25 #26 #27 #28).

```bash
# 1. Start the backing services (Postgres, LocalStack, psp-simulator)
docker compose -f docker/compose.yaml up -d

# 2. Run the API (Flyway migrates, queues provision themselves at boot)
./mvnw spring-boot:run -pl apps/api

# 3. Create a payment (dev seed provides DARGENT_API_KEY)
curl -sX POST http://localhost:8080/v1/payments \
  -H "Authorization: Bearer $DARGENT_API_KEY" -H "Idempotency-Key: demo-1" \
  -H 'Content-Type: application/json' \
  -d '{"amount": 10000, "description": "Order #123", "expiresIn": "PT30M"}'

# 4. Pay the QR at the payer bank (simulator)
curl -sX POST http://localhost:8090/cobs/{txid}/payments

# 4. Watch it land (will show PENDING until the PSP's webhook arrives — E4 complete)
curl -s http://localhost:8080/v1/payments/{txid}   # → "status": "PENDING"
```

Swagger UI (dev only): `http://localhost:8080/swagger-ui.html` · Simulator chaos knobs: see `docker/.env.example`.

## Testing

```bash
./mvnw test                          # unit only (fast, no containers)
./mvnw verify                        # unit + integration (Testcontainers: Postgres + LocalStack + WireMock)
./mvnw verify -Dskip.unit.tests=true # integration only
./mvnw verify -Dgroups=chaos         # chaos & race suites (webhook drop, concurrent refunds, outbox kills)
```

The full scenario catalog lives in the [testing playbook](docs/testing-playbook.md). Every test guards a
money or race guarantee; the reconciliation scenario ("webhook suppressed → reconciler confirms") runs in CI.

## CI/CD & deployment

Pipeline **now (M0/M1 scope):** boundary gates (ArchUnit + script) → unit + integration (Testcontainers) →
production build → image build with **non-root gate**. That is the entire pipeline that runs today — by design.

Pipeline **at M4 (target, per [design](docs/design.md) §11.1):** SpotBugs → OWASP Dependency-Check → combined
coverage gate → Trivy (2-pass) + SBOM → CodeQL + Dependency Review → **runtime smoke** (E2E happy path +
reconciliation chaos + graceful shutdown under load) → k6 performance (consultative).

An annotated tag `vX.Y.Z` produces the semver image + GitHub Release with the jar and the SBOM of the exact
shipped image. Deployment is **blue-green by immutable tag** with a 10%/30s canary and instant rollback
(deploy scripts land at M4). Procedures: [release runbook](docs/release-runbook.md).

## Current state

**E6 + E7 ledger (S1–S5) + E10 notifications (S0–S7) complete on `main` (E7 S5 `33462467004` #59 green;**
**E10 loop/poison `33674334484` #113 / `33675295464` #114; E10 read API `33683261976` green). Ledger**
**consumes `payment.confirmed`, journals double-entry postings, maintains balance proof + rebuild, and**
**settles the full available balance behind `DARGENT_LEDGER_CONSUMER_ENABLED` (off by default).**
**Notifications consume events into `notifications.notification` behind `DARGENT_NOTIFS_CONSUMER_ENABLED`**
**(off by default) and expose `GET /v1/notifications` (tenant-scoped read API). M2 is now ✅.**

| Milestone | Scope | Status |
|---|---|---|
| M0 — Skeleton | Maven multi-module, ArchUnit gates, compose, CI, Flyway per schema, queue provisioning | ✅ |
| M1 (E3) — Create path | Create cob → PENDING → webhook → CONFIRMED; idempotency; API keys; canonical errors | ✅ |
| M2 (E4) — Webhook intake | `POST /webhooks/psp` fail-closed HMAC, anti-replay, dedupe, conditional confirm | ✅ |
| M2 (E6) — Events backbone | Outbox relay → SNS/SQS FIFO with DLQ + retention (at-least-once, `runOnce`-driven ITs incl. E2E anchor) | ✅ |
| M2 (E7/E10) — Events ledger/consumer | Ledger journaling + balance proof/rebuild + settlement (E7 S1–S5 ✓); notifications consumer + `GET /v1/notifications` read API (E10 S0–S7 ✓) | ✅ |
| M3 — Suffering | Refunds, expiration, resurrection, reconciler, settlement, DLQ/backoff/EXHAUSTED/requeue | ☐ |
| M4 — Finish | Metrics, blue-green deploy, runtime smoke in CI, tag releases + SBOM, restore drill | ☐ |
| M5 — Stretch | Card as second Strategy, k6 as hard gate, Redis read cache, webhook reprocessing | ☐ |

## License

MIT (LICENSE file added at M0).
