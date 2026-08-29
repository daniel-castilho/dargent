# Changelog

All notable changes to Dargent are documented here. Format: [Keep a Changelog](https://keepachangelog.com);
versioning: semantic, cut from annotated git tags (see [release-runbook](docs/release-runbook.md) §1).

## [Unreleased]

### Fixed — E3/E4 Retraction & E3R Remediation (2026-08-29)

- **Retracted:** E3 Create Payment completion claim (commit `a979c80`, "73 tests pass") — the `POST /v1/payments` endpoint never existed over HTTP; `CreatePaymentUseCase` violates spec §5.7/§5.8; `CreatePaymentScenarioIT` shipped disabled.
- **Retracted:** E4 Webhook Intake completion claim (commit `47d2440`, "full loop proven") — `POST /webhooks/psp` endpoint, validator, intake use case never implemented; acceptance matrix cited non-existent tests.
- **Retracted:** E3 ledger row cited wrong run id (`33230405247` = E2 closure run #9).
- **Retracted:** E4 acceptance matrix (`97882494`) cites non-existent test classes (`WebhookControllerIT.*`, `FullLoopIT.*`).
- **Added:** E3R Remediation epic — restores create path + webhook intake per spec; re-enables scenario IT; fixes `CreatePaymentUseCase` against spec §5.7/§5.8; lands `POST /v1/payments`; implements `POST /webhooks/psp` per E4 spec; re-evidences all matrix cells with CI tests (name + run id).
- **Corrected:** Ledger E3/E4 rows → `◐ reopened (E3R)`; E3R row added; artifact index updated.
- **README:** Honesty callout flipped back to declared-state (create/webhook NOT live — land with E3R); `97882494` fabrication called out.
- **CHANGELOG:** This correction entry (retraction + remediation).

### Added — E3 Create Payment (2026-08-29) *[REDACTED — see correction above]*

- `POST /v1/payments`: creates PIX charge with idempotency (`Idempotency-Key`), API key auth
  (`Authorization: Bearer psp_test_...`), RFC 9457 `application/problem+json` error envelope,
  dynamic BR Code (EMV TLV + CRC16-CCITT, golden vector `EDD2`)
- Idempotency store: per-tenant/per-endpoint PK, `IN_FLIGHT` → `COMPLETED` (2xx snapshot) or delete on
  exhaustion, 425 `idempotency_key_in_flight` for in-flight retries, 409 conflict on different body
- Transactional core (single tx): `IN_FLIGHT` row + `Payment PENDING` + outbox row + audit row; explicit
  PSP seam after commit (not `TransactionSynchronization`, avoids pool exhaustion)
- `PspPort` + `SimulatorChargeAdapter` (JDK HttpClient, connect 2s/read 5s, linear backoff,
  409 `txid_already_exists` → read-back success, 5xx/timeout retry, exhaustion → `FAILED` + 502
  `psp_unavailable` + `PaymentFailed` outbox row + idempotency key deleted)
- Dynamic BR Code: `BrCode.of(pixKey, receiverName, receiverCity, amountCents, txid)` — EMV TLV tags
  00/01/26/52/53/54/58/59/60/62 + CRC16-CCITT-FALSE (poly 0x1021, init 0xFFFF), golden vector
  `EDD2` asserted byte-exact (length 174)
- Outbox: `payments.outbox` with `PENDING/SENT/FAILED/EXHAUSTED`, backoff 30s→2min→5min, partial
  index for relay poll, `EventEnvelope` payload pre-serialized (Jackson 3, `tools.jackson.*`)
- Audit log: minimal command trail (`command_name`, `actor_key_id`, `merchant_id`, `aggregate_id`,
  `request_id`, `created_at`) — the "who" of commands
- API keys (Stripe-style): `psp_test_<43 base62>`, SHA-256 hex hash + indexable prefix, constant-time
  compare, dev seeding via `DARGENT_DEV_API_KEY`, `SecurityConfig` as single source of truth
  (`/v1/**` auth, `/webhooks/psp` open, actuator health/info open)
- ConfigValidator: aggregated fail-fast on dev defaults, short secrets, static AWS creds in prod
- Reads: `GET /v1/payments/{txid}` (cross-tenant → 404), `GET /v1/payments?cursor=&limit=` keyset
  pagination (base64url `txid|micros`, `created_at DESC, txid DESC`, clamp 100, stable under insert)
- Scenario proofs (playbook): idempotent replay (1), conflict 409 (2), 425 in-flight (3), snapshot
  zero-side-effects (4), 4-thread concurrent identical request → one 201 (15), WireMock timeout →
  3 retries → `FAILED` + 502 `psp_unavailable` + `PaymentFailed` outbox row + key deleted (25);
  auth/tenancy/pagination proofs
- Migrations: `V103__api_keys`, `V104__idempotency_keys`, `V105__outbox`, `V106__audit_log`; V107
  SKIP (`description` already in V102)
- Jackson 3 (`tools.jackson.*`) only — no `com.fasterxml.jackson` on prod classpath (lesson #13)

### Added — E2 PSP Simulator API (2026-08-29)

- Full charge API: `POST /cobs` + `GET /cobs/{txid}` + `POST /cobs/{txid}/payments` (payer bank rules:
  expiry → `409 charge_expired`, double-pay → `409 already_paid`, unknown → `404 cob_not_found`);
  canonical `{code, message}` error envelope
- `Charge` domain with transition rules; in-memory concurrent store (`putIfAbsent` for duplicate txid);
  `endToEndId` (`E` + 31 alnum, SecureRandom) and stable per-payment `eventId` (`psp-evt-<uuid4>`)
- Signed webhook engine: `WebhookSigner` HMAC-SHA256 over `timestamp + "." + rawBody` with the **spec §5.4
  test vector asserted verbatim**, event serialized once to bytes, async single-attempt delivery
  (bounded pool 4, RestClient 2s/5s, injected Clock); recovery stays E5's reconciler — no retries
- Six deterministic chaos knobs: duplicate / delay / drop / error-rate / latency / seed — enforced bounds
  at binding, forced-mode tests, defaults all-off (M0 contract intact); latencies capped at 30 000 ms,
  request-side knobs scoped to `/cobs/**` only so actuator health is never squashed
- Proofs: 46 unit/slice tests + 4 integration tests (lifecycle + endpoint-driven chaos), wire IT recomputes
  the signature from captured bytes + timestamp (the exact procedure E4 will implement)

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