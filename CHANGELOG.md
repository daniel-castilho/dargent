# Changelog

All notable changes to Dargent are documented here. Format: [Keep a Changelog](https://keepachangelog.com);
versioning: semantic, cut from annotated git tags (see [release-runbook](docs/release-runbook.md) §1).

## [Unreleased]

### Added — E8 Refunds, Block 2: balance guard, refund races, auditor refund legs (2026-09-03)

- **Refund balance guard IT over HTTP** (S6, scenario 23): `POST /v1/refunds` returns `409
  insufficient_merchant_balance` with zero writes when the merchant `:available` ledger balance is
  below the requested net refund; a guard-pass refund posts entry [3]+[4] exactly (`5940/−6000/60`
  golden vector for a 40% refund of 100.00/fee 1.00).
- **Concurrent refund races** (S6, scenarios 12 + 23): two concurrent 60% refunds (sc.12) are
  serialized by the `FOR UPDATE` payment lock + version guard — exactly one `201`, the other
  `409 refund_exceeds_remaining`, `refunded_cents` stays exactly 6000. Two concurrent `refund.created`
  ledger events draining the same `:available` account (sc.23) are arbitrated by the conditional
  `UPDATE ... WHERE balance_cents >= :drain` — one POSTED, the other IGNORED with a
  `refund_skipped_balance` audit row; ledger proof stays balanced.
- **Skip-audit actor fix** (S6): the `postRefund` skip-audit path now writes the
  `SYSTEM_AUDIT_ACTOR` sentinel + the real `merchant_id` into the `refund_skipped_balance` audit row,
  satisfying `ledger.audit_log.actor_key uuid NOT NULL`.
- **Journal coverage auditor refund legs** (S7, DEBT-4 extended): `PHASE_C` flags a refund row with no
  matching POSTED `refund.created`; `PHASE_D` flags a POSTED `refund.created` with no matching refund
  row. Uses `payments.refunds ⋈ payments.payments` (same-schema, AGENTS §2.4).
- **Auditor connection-leak fix** (S7, DEBT-5 paid): `runOnce()` now materializes all four queries with
  `.list()` instead of `.stream().collect(...)`, which leaked one DB connection per query per scan and
  exhausted the Hikari pool under IT load; the auditor now passes 6/6 including the two new legs.

### Added — E5 Expiration, Resurrection & Reconciliation (2026-09-02)

- **Expiration scheduler** (V111): partial index `(expires_at) WHERE status='PENDING'`, conditional
  `UPDATE ... WHERE status='PENDING'` to `EXPIRED` (the database arbitrates the race, DEBT-1 closed).
- **Reconciler** (S3): polls the PSP's `GET /cob` truth for due/expired payments and confirms
  `late=true` on its own when the PSP reports paid — signed-webhook loss is no longer a lost payment.
- **Resurrection** (S4): an `EXPIRED` payment paid late resurrects to `CONFIRMED` (`late=true`) via the
  shared confirm path, audited; exactly-once via conditional `UPDATE ... WHERE status IN (PENDING,EXPIRED)`.
- **Give-up window** (S5): past `expiresAt + DARGENT_RECONCILER_GIVE_UP_HOURS` the reconciler stops
  probing (clears `next_reconcile_at`) and audits `reconciliation_window_expired` — no endless resurrection.
- **Consistency legs** (S6, scenarios 9 + 10): duplicate/late reconciliation delivery and replayed
  reconciliation keep the final state consistent — one outbox `payment.confirmed` + one audit, never doubled.
- **Journal coverage auditor** (S7, DEBT-4): composition-root detector that flags CONFIRMED payments
  lacking a POSTED `payment.confirmed` journal event (Phase A) and vice-versa (Phase B), via two
  per-schema SELECTs + Java set-diff (no cross-schema JOIN). Gated `DARGENT_JOURNAL_COVERAGE_ENABLED`
  (default false). Detect-and-alarm only: writes `payments.audit_log journal_coverage_gap` rows.

- **Closed:** DEBT-1, DEBT-4. **M3 remains open** for E8 (refunds) and E9 (delivery hardening).

### Added — E10 Notifications Consumer + Read API (2026-09-02)

- **Notifications schema** (`modules/notifications` V101, schema-per-module): `notifications.notification`
  with `event_id` unique dedupe key, `payload` JSONB (already envelope-validated), and a merchant-scoped
  keyset index `(merchant_id, created_at DESC, id DESC)` backing §7 pagination.
- **Event reader** parsing `io.dargent.shared.events.EventEnvelope` strictly (poison → IAE, no ack → DLQ),
  Jackson 3 only, mirroring ledger's reader shape with zero ledger imports.
- **Ingestion use case** (§3–§4): dedupe via `INSERT … ON CONFLICT (event_id) DO NOTHING` (at-least-once),
  translation happens at the boundary (AGENTS §3.6); non-consumable envelope kinds → IGNORED.
- **SQS FIFO consumer** (`DARGENT_NOTIFS_CONSUMER_ENABLED`, off by default): batch, long-poll, ack-only-on
  commit, poison → DLQ with redrive; message-dedupe by `eventId`.
- **Read API** (§7): `GET /v1/notifications` — tenant from the authenticated principal (AGENTS §3.7,
  never from query/path/body, no `merchant_id` emitted), `type` filter, `limit` (1..100, default 20),
  opaque base64url keyset `cursor` over `(created_at, id) DESC`, `payload` never returned. Route declared
  explicitly in `SecurityConfig`. Response fields camelCase to match Payments API serialization
  (owner adjudication 2026-09-02 — spec §7 amended).
- **Tests**: reader/use-case/consumer unit tests + `NotificationLoopIT` + `NotificationPoisonDlqIT`
  (S1–S5, CI #113 #114) + `NotificationsApiIT` (S6: shape, type filter, pagination cursor round-trip,
  400/401). Full reactor `verify` (43 API ITs) + ArchUnit + `check-boundaries.sh` green.

### Added — E7 Ledger Core (2026-08-31)

- **Ledger schema** (`modules/ledger/db/migration/ledger` V202–V206, schema-per-module): `events`
  (ingestion + dedupe by `event_id` PK), `journal_entries` (`event_id` unique ref to events; nullable
  per V205 so settlement entries with no envelope event can be written), `postings` (DEBIT/CREDIT,
  amount > 0), `balances` (credit-positive projection), `settlements` (`idempotency_key` unique), and
  `audit_log` (ledger's own command trail — no dependency on payments' audit table).
- **Event ingestion use case** (§5.3): strict envelope reader (poison → no ack → DLQ), dedupe via
  `INSERT … ON CONFLICT (event_id) DO NOTHING` first statement of the tx, `payment.confirmed` posts
  DEBIT `payments:processing` / CREDIT `fees:revenue` / CREDIT `merchant:{id}:available` with the
  `fee + net == amount` gate → REJECTED; non-confirmed → IGNORED.
- **SQS consumer + fan-out topology** (§5.1): `deploy/localstack-init.sh` v2 adds ledger FIFO queue +
  DLQ + redrive (`maxReceiveCount=5`) + SNS subscription; `SqsEventConsumer` (batch ≤ 10, long-poll,
  ack-only-committed, poison→DLQ) behind `DARGENT_LEDGER_CONSUMER_ENABLED`.
- **Settlement + reconcile + HTTP surface** (§5.4–§5.6): `SettlementUseCase` (full available balance in
  one tx, `SELECT … FOR UPDATE` race arbitration, idempotent replay by key, `no_balance_to_settle` 409);
  `LedgerReconciliationUseCase` (proof diagnostic with counts, rebuild-from-journal); `LedgerController`
  exposes `GET /v1/ledger/accounts/{account}/balance`, `GET /v1/ledger/proof`, `POST /v1/ledger/rebuild`,
  `POST /v1/ledger/settlements` — routes declared explicitly in `SecurityConfig`.
- **Tests**: 27 ledger unit tests (reader strictness, posting math, dedupe, settlement guards/replay/race,
  proof, rebuild) + `LedgerMigrationIT` (V202–V206 on PG16); full reactor `verify` green.



- **Relay engine** (`OutboxDeliveryUseCase.runOnce()`): claim via `FOR UPDATE SKIP LOCKED`, strict-Jackson
  eventId parse, publish, conditional mark `SENT`; publish error → attempt bump + backoff (30s → 2min →
  5min cap), rows stay `PENDING` (E9 owns `EXHAUSTED`). All cycles driven through `runOnce()` in tests —
  zero sleeps, injected `Clock` (spec §5.1).
- **`SnsEventPublisher`** (AWS SDK v2 direct, url-connection client): FIFO topic publish with
  `MessageGroupId = aggregate_id` (txid), `MessageDeduplicationId = eventId`, `Subject = type`, body =
  stored jsonb **verbatim**; per-call timeout override bounding SDK retry amplification (§4.1).
- **Full E3 §5.6 envelope in writers** (owner decision): `EventEnvelopeFactory` builds
  `{eventId(v4), type, version, aggregateId, merchantId, requestId, occurredAt, payload{…}}` in fixed key
  order — the outbox `payload` column IS the wire format (§5.3); webhook envelope carries `requestId=null`.
- **`OutboxId` UUIDv7** row ids (RFC 9562, injected clock) in all writers — V105's comment is no longer a
  lie (§5.5).
- **Retention purge** (§5.4): every Nth cycle deletes SENT rows older than `DARGENT_OUTBOX_RETENTION_DAYS`
  (default 7, BoE-derived) in bounded batches; PENDING/FAILED/EXHAUSTED never purged by E6.
- **Dev topology**: compose LocalStack + idempotent `deploy/localstack-init.sh` (FIFO topic, notify queue,
  DLQ, redrive `maxReceiveCount=5`, subscription); `.env.example` carries all §4.1 rows.
- **`docs/load-test-baseline.md`** BoE section (assumptions-arithmetic, honestly labeled): ~1.16 evt/s
  avg / 23 evt/s peak vs relay ceiling 64 evt/s (workers 2 × batch 32 / poll 1s) — defaults derive from it.
- **Tests**: relay ITs 1–4 on PG16+LocalStack (publish w/ byte-equal body + group/dedup ids, retry
  deferral, two-thread SKIP LOCKED race, purge), **IT5 M2 anchor E2E** (`OutboxDeliveryE2EIT`: API create →
  webhook confirm → `runOnce()` → `payment.confirmed` on the FIFO queue), IT6 topology attrs
  (`AwsTopologyIT`); unit suite for claim/backoff/mark/purge/defect paths.
- **Delivery guarantee (verbatim, §5.6)**: at-least-once, per-payment FIFO ordering, dedup by
  `MessageDeduplicationId=eventId` (5-min window), consumer idempotency by `eventId` = E10's contract.
  Nobody in this repo ever writes "exactly once".

### Fixed — E7 S5 Ledger integration tests (2026-08-31)

- **Six integration tests added** (`apps/api/src/test/java/io/dargent/api/ledger/`): IT1–IT4
  `LedgerMoneyLoopIT` (M2 full loop: HTTP create → webhook → relay → ledger consumer → balanced
  journal + proof; idempotent redelivery; cross-merchant 404; hostile/IGNORED event), IT5/IT5b
  `LedgerSettlementIT` (settle full available balance + concurrent idempotent replay), IT6
  `LedgerPoisonDlqIT` (poison payload → no ack → redrive-to-DLQ). Green on PG16 + LocalStack.
- **Wire-format contract aligned to design.md §7.1 (owner-approved, AGENTS §9d)**: shared `EventEnvelope`
  field `payloadJson` → `payload`; payments `EventEnvelopeFactory` already emitted `"payload":{object}`.
  Ledger `EventEnvelopeReader.read()` now binds via manual `JsonNode` extraction instead of
  `mapper.readValue(raw, EventEnvelope.class)`, which Jackson could not bind (object ↔ String) and had
  been silently nacking the relayed confirmed envelope. Shared stays Jackson-free.
- **Postgres `timestamptz`→`Instant`**: `JdbcLedgerStore` read via `getObject(..., OffsetDateTime.class)`
  `.toInstant()` (4 sites) — direct `getObject(..., Instant.class)` is unsupported by pgjdbc.
- **`Timestamp` SQL-type binds**: `JdbcLedgerStore` journal/postings/settle inserter used
  `Timestamp.from(...)` for `Instant` args (fixes "Can't infer the SQL type … java.time.Instant").
- **Postings/journal FK fix**: `EventIngestionUseCase` now uses `entryId` as the journal `id`, honoring
  `postings_entry_id_fkey`; previously a separate `UUID.randomUUID()` violated the FK.
- **Single-statement idempotent ingestion**: `processMessage` inserts once after deciding the terminal
  status (non-confirmed → IGNORED, invalid confirmed → REJECTED, valid confirmed → RECEIVED then POSTED
  in-tx), removing the silent `ON CONFLICT DO NOTHING` no-op freeze in RECEIVED.
- **Strict payload boundary**: `EventEnvelopeReader.extractPaymentPayload` rejects non-object payloads
  (true boundary validation).

### Docs — E7 S6 (2026-08-31)

- **BoE addendum** (`docs/load-test-baseline.md` §0.1 "Ledger growth addendum"): journal + postings grow
  ~40 MB/day (~100k journal + ~300k postings), **never purged** (append-only) → ~1.2 GB/month, archival is
  E14's row; growth knob is not a retention remedy. Assumptions labeled, not measured.
- **README current state synced**: E7 S1–S5 live (journal + proof/rebuild + settlement behind
  `DARGENT_LEDGER_CONSUMER_ENABLED`); M2 row marked ◐ — E10 notifications pending, honest per spec §9.

### Fixed — E6 (2026-08-30)

- **`SimulatorChargeAdapter` proxy poisoning**: constructor set `System.setProperty("http.proxy*", "")`,
  which broke the AWS `UrlConnectionHttpClient` built in the same JVM (SNS publish → "Connection refused").
  Removed along with debug `System.out` cruft; the PSP client keeps its own NO_PROXY selector.
- **LocalStack IT credentials**: `AwsTopologyIT`/`OutboxDeliveryE2EIT` built SQS/SNS clients on the default
  credentials chain (locally satisfied by ambient env, absent on CI — run #42 red). Now pinned
  `StaticCredentialsProvider(test,test)` like `OutboxRelayIT` (#43 green).

### Fixed — E3/E4 Retraction & E3R Remediation (2026-08-30)

- **Retracted:** E3 Create Payment completion claim (commit `a979c80`, "73 tests pass") — the `POST /v1/payments` endpoint never existed over HTTP; `CreatePaymentUseCase` violates spec §5.7/§5.8; `CreatePaymentScenarioIT` shipped disabled.
- **Retracted:** E4 Webhook Intake completion claim (commit `47d2440`, "full loop proven") — `POST /webhooks/psp` endpoint, validator, intake use case never implemented; acceptance matrix cited non-existent tests.
- **Retracted:** E3 ledger row cited wrong run id (`33230405247` = E2 closure run #9).
- **Retracted:** E4 acceptance matrix (`97882494`) cites non-existent test classes (`WebhookControllerIT.*`, `FullLoopIT.*`).
- **Added:** E3R Remediation epic — restores create path + webhook intake per spec; re-enables scenario IT; fixes `CreatePaymentUseCase` against spec §5.7/§5.8; lands `POST /v1/payments`; implements `POST /webhooks/psp` per E4 spec; re-evidences all matrix cells with CI tests (name + run id).
- **Corrected:** Ledger E3/E4 rows → `◐ reopened (E3R)`; E3R row added; artifact index updated.
- **README:** Honesty callout flipped back to declared-state (create/webhook NOT live — land with E3R); `97882494` fabrication called out.
- **CHANGELOG:** This correction entry (retraction + remediation).

### Closed — E3R Complete (2026-08-30)

- **BD-1…BD-14:** All defects fixed with CI evidence (runs #19 #24 #25 #26 #27 #28)
- **MS-1…MS-3:** All milestones implemented (endpoints live)
- **TD-1…TD-11:** All tech debt resolved (IT enabled, docs committed, evidence CI-cited)
- **BD-12:** Audit actor sentinel UUID for webhook callbacks (BD-14 ratification)
- **BD-13:** `paidAt` parsing guarded inside strict block + poison IT
- **BD-11:** Atomicity failure-injection IT (outbox trigger → 500 → RECEIVED → redeliver → PROCESSED)
- **BD-14:** Sentinel audit actor ratified (V106 NOT NULL stands; javadoc + IT assert)
- **Matrix:** All cells green with CI run IDs (#18 #19 #22 #24 #25 #26 #27 #28)
- **E3/E4 ledger rows:** `✅` flipped (run #30 `33333739409`)

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