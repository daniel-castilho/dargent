# Changelog

All notable changes to Dargent are documented here. Format: [Keep a Changelog](https://keepachangelog.com);
versioning: semantic, cut from annotated git tags (see [release-runbook](docs/release-runbook.md) §1).

## [Unreleased]

### Added — E1 Payment Domain & State Machine (2026-08-28)

- `Txid` (25-char `[A-Z0-9]`, D4) + `SecureRandomTxidGenerator`; `EndToEndId` (`E` + 31 alnum)
- `BpsRate`/`FeeBreakdown`: fee = `floor(amount × bps / 10_000)`, net = amount − fee, reversal formula —
  property-tested (jqwik)
- `Payment` aggregate with guarded state machine (PENDING ↔ CONFIRMED/EXPIRED/FAILED/REFUNDED, resurrection
  with `lateConfirmation` audit flag), typed domain exceptions, and domain events (`PaymentEvent` +
  concrete records) drained per transition
- `PaymentRepository` port with lost-race contract (`updateIfVersionMatches` → `false` on stale version,
  adapter never throws) + in-memory fake + shared contract test suite
- `V102__create_payments_table.sql` (schema `payments`): uuid PK, unique `txid`, money as `bigint` cents
  (D5), status CHECK, optimistic `version`, fee/net columns, `late_confirmation`, `refunded_cents`
- JPA adapter (`PaymentEntity`/`PaymentMapper`/`PaymentJpaAdapter`) at the adapter edge only (D14) —
  transitions are explicit conditional UPDATEs; the DB arbitrates races (AGENTS.md §3.2)
- Integration proofs on real PostgreSQL 16 (Testcontainers): `PaymentJpaAdapterIT` (contract suite on the
  adapter) + `PaymentConcurrentTransitionIT` (8 threads, exactly one winner)

### Added — M0 Skeleton (2026-08-28)

- Maven multi-module structure by bounded context: `modules/{shared,payments,ledger,notifications}`, `apps/{api,psp-simulator}` (design.md §3.2)
- Domain seeds: `Money` value object, `EventEnvelope`, `PaymentStatus`, `EntryDirection`, `NotificationType`
- ArchUnit architecture tests per module + boundary gate proof test (M0 acceptance criterion)
- `scripts/check-boundaries.sh` — import/FQN boundary gate for CI (double net with ArchUnit, prod-only scan)
- Flyway per-module migration locations with gap-versioned numbering (payments V1xx, ledger V2xx, notifications V3xx)
- Docker Compose runtime: Postgres 16, LocalStack, NGINX blue-green topology, api blue/green fleets, psp-simulator
- GitHub Actions CI: boundary gate → unit + IT (Testcontainers) → image build with non-root gate
- Foundation documents: design.md (EN, canonical), coding-standards, testing-playbook, observability, slos,
  load-test-baseline, release-runbook, twelve-factor, data-model-decisions, lessons, AGENTS.md

## [0.1.0] - 2026-08-28

### Added

- Project skeleton: Maven reactor, module boundaries, CI pipeline, compose topology
- Money value object with cents-based arithmetic (no float)
- Event envelope contract (broker-agnostic)
- Per-module Flyway migrations (schema-only in M0)
- Architecture tests with deliberate violation proof (`BadDomainFixture`)
- Boundary gate script (prod-only scan per lessons.md #11)
- Non-root container images (uid 10001)
- Compose stack with healthchecks (postgres, localstack, api-blue/green, psp-simulator, nginx)

### Fixed

- Boundary script restricted to production sources only (`*/src/main/java/*`) to avoid flagging test fixtures
- MigrationIT uses explicit Flyway configuration via `@SpringBootTest(classes={...})` for reliable schema creation
- Notifications module added seed `NotificationType` for ArchUnit test to pass