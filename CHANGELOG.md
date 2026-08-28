# Changelog

All notable changes to Dargent are documented here. Format: [Keep a Changelog](https://keepachangelog.com);
versioning: semantic, cut from annotated git tags (see [release-runbook](docs/release-runbook.md) §1).

## [Unreleased]

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