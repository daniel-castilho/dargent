# Changelog

All notable changes to Dargent are documented here. Format: [Keep a Changelog](https://keepachangelog.com);
versioning: semantic, cut from annotated git tags (see [release-runbook](docs/release-runbook.md) §1).

## [Unreleased]

### Added (M5 S2 — Redis read cache, D3 idempotent-replay)

- **Idempotent-replay read cache (the ONE hot read path, D3).** `CachedIdempotencyStore`
  decorates the JDBC idempotency store: completed replay snapshots are cached in Redis
  (TTL-bounded, write-through on `markCompleted`, read-through on completed DB reads,
  evicted on key delete). Only COMPLETED rows are cached — IN_FLIGHT arbitration stays
  DB-owned; cached records carry the request fingerprint so 409-conflict semantics are
  identical from cache or DB. `IdempotencyStore.markCompleted` gained the fingerprint
  parameter (the JDBC store ignores it for SQL — insert wrote it).
- **Fail-open by contract.** Every cache interaction is guarded: Redis failure → DB
  fallback, correctness unchanged, counted in `dargent_cache_failopen_total`, never
  surfaced to the money path. Proven by `ReplayCacheIT` stopping the Redis container
  mid-test (byte-equal replay from the DB fallback). Command timeout capped at 1s.
- **Default OFF (carved):** `DARGENT_CACHE_REDIS_ENABLED=false` → zero Redis beans, DB
  direct (Boot's Spring Data Redis auto-config excluded in application.yaml; the cache's
  own conditional `CacheConfiguration` provides the client only when enabled). Surface:
  `DARGENT_CACHE_REDIS_ENABLED` / `DARGENT_CACHE_REDIS_URI` / `DARGENT_CACHE_REDIS_TTL`
  (default PT5M). Compose: `redis` service behind opt-in profile `cache`.
- **Metrics:** `dargent_cache_hits_total` / `dargent_cache_misses_total` /
  `dargent_cache_failopen_total` (label `path=idempotency-replay`) + observability.md §3.
- **Security (CodeQL CWE-117):** every `log.warn` site in the cache adapter logs the
  client-controlled `Idempotency-Key` through `logSafe()` (line breaks cut) — the header is
  length-validated only, so a forged key must not be able to forge log lines. Lesson #20.

### Fixed (M5 B1 selo — runtime-smoke leg 7 red ×2, hotfix smoke)

- **smoke.sh leg 7 asserted a `fee` field the GET contract never emits** (design §6.2
  has no `fee` — documented E12 deviation). Both CI runtime-smoke reds (PR CI + main
  push #286) were this single assertion; legs 1–6 and the card rail handshake were
  green in every run. Leg 7 now asserts what the contract emits on a confirmed card
  GET: amount echo + explicit-null `brcode` (FINDING-S1-2).
- **Extends FINDING-S1-3 masking analysis:** the rail-column no-op (JPA lazy flush)
  masked **two more** runtime behaviors, now visible: the controller detail presentment
  would have emitted a PIX BR Code on card GETs (`railOf` defaulted to `pix`), and the
  reconciler would have polled the PIX cob endpoint for card rows (404 → never
  reconciled). Smoking out: smoke leg 7 (`brcode:null` on card GET) + CardPaymentIT
  reconciler test. No code change needed — the flush fix already removes all three.

### Added (M5 S1 — card as second rail, PR #B)

- **Card payment rail (S1).** A new `CardChargeAdapter` implements the `PaymentRail`
  seam as a second strategy alongside PIX: the same `CreatePaymentUseCase` routes
  `method:"card"` to the card adapter, which returns a PENDING payment with
  `brcode:null` (FINDING-S1-2); the PSP simulator fires the HMAC-signed webhook
  synchronously during the create call, so confirmation follows the identical
  PIX-signed intake path (zero domain edits). Card 402 `card_declined` → FAILED +
  idempotency key deleted + retry as fresh attempt. Cards are reconciled via
  `GET /card-charges/{txid}`.
- **Rail-seam persistence fix (FINDING-S1-3).** `PaymentJpaAdapter.save` now calls
  `em.flush()` after `em.persist()` so that the same-transaction JDBC rail assignment
  sees the inserted row. Without the flush, the assign UPDATE silently matched zero
  rows and the column kept the DDL default `'pix'` — invisible for S0 (PIX default
  masked it), exposed by S1 (card needs `'card'`).
- **Error handling:** `CARD_DECLINED` error code (HTTP 402 `payment_required`) and
  `GlobalExceptionHandler` mapping for `PspDeclinedException`.
- **Smoke script legs 5–7:** card create → 201 PENDING + `brcode:null`, poll confirm
  → CONFIRMED, GET detail → amount + fee echoed.
- **Docs:** `docs/audit-m5-s1.md` — full S1 diff audit disclosing the PspPort contract
  change, rail-seam flush fix (FINDING-S1-3), wiring, tests, and smoke coverage.

### Fixed (E16 queue triage — dependabot updates landed green)

- **OWASP dependency-check pinned 13.0.0 → 12.2.2.** The 13.0.0 line is broken in keyless
  mode (upstream `dependency-check#8715`: an EMPTY string is passed as the NVD key instead of
  null, regression from #8549 — fix merged upstream but unreleased), so runs without the
  secret died with `Invalid API Key, length of 0`. Dependabot PRs receive NO repo secrets, so
  the gate had to genuinely work keyless. Last 12.x restores the E13 design
  (throttled-but-working); the pom no longer reads `env.NVD_API_KEY` — CI passes `-DnvdApiKey`
  only when the secret exists (ci.yml + release.yml). Proven both ways locally.
- **Dependabot queue shutdown:** maven group merged (#42: compile+test 3.9.16, ArchUnit 1.5.0,
  Testcontainers 2.0.5, SpotBugs plugin 4.10.4.1, tools.jackson 3.2.2 + `jackson-annotations`
  2.22 pin in dependencyManagement for Flyway, jqwik 1.10.1, WireMock 3.13.2, AWS SDK 2.54.13;
  SpotBugs 4.10 gate fix in `OutboxLagGauge`); then the CI-action and compose groups (#38
  setup-java 6, #39 login-action 4.6.0, #41 compose group 3 updates). Queue empty as of
  2026-09-09.

### Changed (E16 S5 — dependabot live: weekly, grouped, noise-budgeted)

- `.github/dependabot.yml`: three ecosystems (maven production deps, github-actions SHA-pin
  bumps, docker-compose image tags), weekly Monday cadence, **minor+patch grouped** per
  ecosystem (one review per batch), `open-pull-requests-limit` 5/3/3. Enforcement stays with
  the existing gates (OWASP NVD-keyed CVSS≥7 + Trivy 2-pass) — dependabot is early warning only.
- **Live same-day:** the first grouped PRs opened within minutes of merging (maven group with
  11 updates, compose group, actions pin bumps — PRs #34–#38). **Infra majors fenced:**
  postgres/prometheus/alertmanager semver-major updates are ignored with reasons (stack
  contract, promtool CI pins the same major) — the two major PRs dependabot opened before the
  ignore landed (#35 postgres 18, #36 prometheus v3) were closed deferred, reasons on record.

### Changed (E16 S4 — limiter posture declared: per-instance by design, M5-Redis deferral explicit)

- observability.md §3 gains the posture block: scope key (`X-Forwarded-For` first hop →
  real caller IP), **per-instance in-heap buckets**, quota math per replica (**canary doubles
  the quota for the same caller** — steady state ≈ 2× defaults; 10/90 window ≈ 1.1×), and the
  exact trigger for a shared-store limiter (fleet-wide budget need) — **deferred to M5 with
  rationale** (`tasks/m5-scoping.md` D3). runbook §7 row updated to match. Path A per the
  Q-batch leaning; no silent carry-over — this entry IS the disposition.

### Added (E16 S3 — honest k6 run: spine ON + default limiter, published beside the baseline)

- Same `scripts/load/k6-money-path.js`, same 24 VUs / 2m30s — but the **production-shaped
  environment**: demo overlay (relay/ledger consumer/reconciler/expiration ON) + **default**
  webhook abuse limits (100 burst / 0.5 rps / 64 KiB). Two runs, published BESIDE the 414 rps
  happy-path as a two-row comparison in `docs/load-test-baseline.md`.
- **The delta IS the result:** HTTP layer never degraded (440 rps, 0.00% errors, create p95
  37.38 ms), but the PSP's webhook burst exhausts the per-IP bucket in seconds — confirmations
  shift from webhook to the **reconciler** (94%/86% within the 90 s k6 deadline across the two
  runs; the rest confirmed later or expired per policy — the system stayed correct, the path
  shifted). This honest number **seeds the M5 k6-gate threshold decision (D4) — recorded,
  not gated** (M5 fence).

### Added (E16 S2 — PITR v2: off-disk WAL, pgdata-volume destruction survives)

- `scripts/pitr-rehearsal-v2.sh`: the E15 S5 caveat answered — WAL archive and base snapshot on
  **separate named docker volumes**, and the disaster is the **destruction of the pgdata volume
  itself** (`docker volume rm` after `kill -9`). Recovery onto a FRESH volume replayed every
  post-base txn from the surviving volumes: **A=3 000 + B=1 500 recovered, ΣDR=ΣCR, projection==
  lines, measured RPO ≈ 6–8 s** (same bound as v1 — `archive_timeout=5s` dominates, not topology).
- CI: dispatch-only **`pitr-drill`** job (Q-batch disposition: CI-ified as dispatch; NOT wired
  into release.yml — the dump-restore drill stays THE release gate; recorded in the drill doc).
- `docs/drills/pitr-v2-2026-09-08.md`: procedure, verbatim transcript, honest v1↔v2 comparison
  (survives-volume-destruction gained at ≈0 RPO cost), residual limits, two new gotchas carved
  (fresh volumes mount root-owned — archive writes fail EACCES silently; canonical tar names).

### Added (E16 S1 — Alertmanager, logging-stub receiver, amtool in CI)

- `metrics` compose profile gains **Alertmanager** (`prom/alertmanager:v0.27.0`) and a
  **webhook-logger stub** receiver (`docker/alertmanager/` — one-file stdlib HTTP sink; NO pager,
  NO external sink by fence). Prometheus `alerting:` section points at it. Routes: `critical`
  (repeat 5 m) / `warning` (repeat 4 h) → the stub; the route tree matches the E15 S2 rule labels.
- CI: **`amtool check-config`** step beside promtool (additions-only — a broken route tree is a
  red build). Local wiring evidence: an alert posted to the Alertmanager API was routed and
  logged by the stub (`DWARF_LEDGER_PROOF_FAIL`, severity=critical, end-to-end).
- observability.md §5 gains the Alertmanager paragraph (routes, stub, how to point a real
  receiver later); epics.md mints the E16 row (the former "E16 Stretch batch" renamed to M5 —
  its scoping package lives channel-side, `tasks/m5-scoping.md`).

## [1.1.0] - 2026-09-08

Operational hardening release: the complete E15 epic. Webhook abuse controls close DEBT-8,
alert rules become tested code, the load baseline is published, the restore drill is proven at
production-like scale, PITR is rehearsed with a measured RPO, and the postJournal twins are
consolidated (DEBT-7). No API contract changes; no schema changes; no new app env vars beyond
the webhook abuse controls.

### Added (E15 S3 — k6 money-path baseline, published number)

- `scripts/load/k6-money-path.js`: money-path load script (create → idempotent replay → pay →
  confirm-poll), SLO thresholds asserted in-script. **Consultative — NOT a CI gate.**
- `docs/load-test-baseline.md`: published run (2026-09-08) — **59 620 requests / 414 rps at
  24 VUs, 0.00% checks failed, 0 HTTP errors**; p95 create 17.08 ms / replay 3.04 ms /
  pay 1.0 ms / confirm 2.58 ms; e2e confirm roundtrip p95 56 ms. Hardware + date + commit +
  k6 image digest disclosed; raw summary preserved (`k6-baseline-2026-09-08.raw.out`).
  Webhook abuse limits raised for the run only (spec §4: tests tune explicitly).

### Added (E15 S4 — restore drill at scale, the "36 KB seed" answer)

- `restore-drill-scale` dispatch-only CI job (`DRILL_SCALE_SEED_TXNS=50000`): SQL bulk-seed
  mirroring the consumer's real posting shape (balanced journals/postings/balances), then the
  full backup → destroy → restore → verify drill. **Measured at scale (run `34244399134`):
  50 000 txns / 150 000 postings, dump 5.6 MB, ΣDR=ΣCR=264 980 000, RTO 23 s**, post-restore
  txn CONFIRMED. `scripts/ci-restore-drill.sh` default `0` keeps the E14 small drill intact.
- `docs/drills/restore-scale-2026-09-08.md`: record with the measured three-point delta
  (36 KB/3 txns → ~20 s; 2.28 MB/20k → 21 s; 5.6 MB/50k → 23 s — RTO flat, measured never
  extrapolated).

### Added (E15 S5 — PITR rehearsal, measured RPO)

- `scripts/pitr-rehearsal.sh`: standalone Postgres 16 PITR harness (archive_mode → seed A →
  `pg_basebackup -Ft -X stream` → post-base side effects → `recovery_target_lsn` → `pg_switch_wal`
  → **kill -9** → base+WAL restore → replay to target, `recovery_target_action=pause` →
  validation at the recovered state). **Measured: every post-base txn recovered via WAL replay,
  ΣDR=ΣCR at the recovered state, achieved RPO ≈ 6 s** (bounded by `archive_timeout=5s`).
- `docs/drills/pitr-2026-09-08.md`: procedure + measured RPO + honest limits (off-host archive
  declared as the runbook §6 gap). Local-documented-only per Q-batch — the record says which.
- AGENTS **amendment (f)**: research before trial-and-error (born from this rehearsal's three
  silent failure causes — docker-library/postgres#146, PG docs §26.3.4 step 7, PG docs §20.5.6).

### Changed (E15 S6 — DEBT-7 RESOLVED, Path A)

- `JdbcLedgerStore`: the `postJournal`/`postJournalWithoutBalances` twins consolidated behind
  one guarded implementation `postJournalCore(entry, updateBalances)` — payment path posts with
  balance upserts, refund path posts with `false` (the E8 conditional drain owns the balance
  writes atomically). Full suite green; coverage floors PASS (ledger 86.9 vs 87.3 same-tree
  baseline); E8/E9 guarantee ITs re-evidenced green (RefundRaceIT 2/2, RefundBalanceGuardIT
  2/2, RefundFlowIT 2/2, Scenario20NoDoubleJournalIT 1/1, JournalCoverageAuditorIT 6/6,
  JournalBalanceDbBarrierIT 4/4).

### Governance (E15)

- BLOCK 1 (S0–S3) audited and approved with deviations declared: `docs/audit-e15-block1.md`
  (bite-proof executed as a closed scratch PR — run `34228122177` red — per E13/E14 probe
  precedent; S3 webhook-limit tuning per spec §4).

### Added (E15 S2 — Prometheus alert rules, tested in CI)

- `docker/prometheus/rules/alert-rules.yml`: 7 rules (proof-fail → critical freeze-deploys;
  outbox lag > SLO / DLQ depth / signature storm / rows EXHAUSTED / 429-storm / 413-storm →
  warning), each with a release-runbook §7 runbook anchor in annotations. Thresholds anchored to
  `docs/slos.md` (S6/S7).
- `docker/prometheus/rules-tests/alert-rules.test.yml`: `promtool test rules` covers EVERY rule
  with one firing case (injected series) + one quiet case (healthy series) — rule-fires and
  rule-quiet negative paths proven. Runs in CI on every push; an untested/red rule is a build
  breaker.
- CI: promtool `check config` + `check rules` + `test rules` step (docker `prom/prometheus:v2.53.0`);
  compose `metrics`-profile Prometheus mounts the rules dir.
- observability.md §5 alert table + anchors.

### Added (E15 S1 — webhook abuse controls, DEBT-8 real closure)

- In-app abuse controls on the public webhook route (`POST /webhooks/psp`), the only `permitAll`
  route: `WebhookAbuseControlFilter` — per-IP token-bucket **rate limit → 429** (zero side
  effects: nothing persisted, no use case invoked) and **body cap → 413** (decided on
  `Content-Length` before the body/HMAC is consumed; bounded read guards chunked/lying headers).
  Valid traffic under limit flows byte-identically (regression-proven by `WebhookIntakeIT`).
- New canonical error codes `payload_too_large` (413) and `rate_limited` (429).
- New env surface (defaults generous, never trip demo/smoke): `DARGENT_WEBHOOK_RATE_LIMIT_CAPACITY`
  (100), `DARGENT_WEBHOOK_RATE_LIMIT_REFILL_PER_SECOND` (0.5), `DARGENT_WEBHOOK_BODY_CAP_BYTES`
  (65536).
- New ITs: `WebhookRateLimitIT` (burst → 429 → recovery window via injected clock; zero
  money-path side effects) and `WebhookBodyCapIT` (413 before HMAC; exact-cap boundary flows;
  chunked capped by bounded read).
- 10th frozen metric series `dargent_webhook_rejections_total{reason=rate_limited|body_too_large}`
  (pre-registered at 0, E12 N8 convention) — feeds the E15 S2 abuse alert rule.

### Changed (E15 S1)

- **DEBT-8 RESOLVED** (AGENTS §8): threat-model surface-1 DoS row now mitigated in-app with NGINX
  as defense-in-depth; runbook §7 incidents table gains the 429/413 storm row.

### Added (E15 S0 — post-release truth pass)

- `docker/compose.demo.yaml`: demo overlay booting the full payments spine ON (relay, ledger
  consumer, reconciler, expiration) — `docker compose -f docker/compose.yaml -f docker/compose.demo.yaml up`
  proves end-to-end journaling and reconciler self-heal with zero hand-exported env vars
  (the E14 S6.5 rider, owner-adjudicated 3/3).
- README demo line + cover tense fix: "E14 cuts v1.0.0" → "E14 cut v1.0.0 (tag `v1.0.0` @
  `601a669`, 2026-09-07)".
- `docs/epics.md`: E15 row minted (◐ operational hardening, post-1.0.0); the former "E15 Stretch
  batch" renumbered to **E16** (dependency graph updated).
- E15 commissioning package committed under `tasks/e15-ops/` (prompt, spec, backlog, sequence,
  Block-1 execution prompt) with corrections carved from the Q-batch: route fixed to
  `POST /webhooks/psp` (the `/v1/` prefix never existed), PR numbering adjusted to the merged
  reorg PR (#13), and the "104 ids" claim resolved as unverifiable (PR #12 body scanned — zero
  occurrences; true counts: 89 ids at E14 S0 relint, 105 ids at E15 start).

## [1.0.0] - 2026-09-07

First full release: the complete payment lifecycle over the simulated PIX rail with
double-entry ledger, delivery hardening, blue-green deployment, and full CI quality/security
gates. Architecture: modular monolith (payments / ledger / notifications / shared),
schema-per-module, events-only cross-module communication, database-arbitrated races.

### Milestone summary (E0→E14)

- **M0 Foundations (E0):** multi-module Maven skeleton (Java 25, Spring Boot 4.1), ArchUnit
  boundary gates, compose stack (PostgreSQL 16, LocalStack SNS/SQS FIFO, NGINX), Flyway
  per-schema migrations, CI from day one.
- **M1 Create path (E1–E4):** PIX BR Code creation with request-level idempotency (byte-equal
  replays), API-key auth (SHA-256 + constant-time), canonical errors; PSP simulator with chaos
  levers; fail-closed signed webhook intake (HMAC-SHA256, 5-min anti-replay, dedupe,
  attack-audit persistence).
- **M2 Events & ledger (E6/E7/E10):** transactional outbox → SNS/SQS FIFO (at-least-once, DLQ);
  append-only double-entry ledger (`ΣDR=ΣCR` journals, balances projection, daily proof +
  rebuild, D+1 settlement); notifications consumer + read API.
- **M3 Suffering (E5/E8/E9):** expiration + exactly-once resurrection; live reconciler
  (webhook-less confirmation — the signature guarantee); partial/total refunds with fee
  reversal, DB-arbitrated concurrency and ledger-backed balance guard; outbox backoff → FAILED →
  EXHAUSTED with audited requeue + admin republish; journal-coverage auditor.
- **M4 Production shape (E11/E12/E13):** JSON logs with ECS correlation + frozen metrics +
  prod lockdown IT; blue-green deploy by immutable tag (canary 10/30/100, instant rollback,
  runtime-smoke + shutdown-under-load gates); quality gates (SpotBugs, Spotless, JaCoCo floors
  on combined data, OWASP NVD-keyed CVSS≥7, Trivy 2-pass + SBOM, CodeQL, Dependency Review,
  evidence-lint).
- **Release engineering (E14):** GHCR images per main commit (`sha-<short7>` + `:edge`);
  tag-triggered release workflow (gates re-run on the tagged commit, semver push, SBOM of the
  exact digest, Release with the shipped jar); backup/restore with manifest-verified restores;
  CI restore drill (destroy→restore→verify, RTO wall-clocked); outbox republish tooling with
  fail-closed preconditions.

### Notable guarantees (proven in CI, not asserted)

- No double charge: idempotency keys + webhook dedupe + consumer `eventId` dedupe.
- No lost confirmation without a webhook: reconciler self-heal (CI-proven chaos scenario).
- Every cent traceable: `ΣDR=ΣCR` per journal + property tests + daily proof job.
- Database arbitrates races: conditional UPDATEs; concurrent refunds one-201/one-409.
- Traffic never returns over an unverified restore: manifest counts + balance proof gate.

### Known issues at release

- F1 (two-release migration review): V301 content changed after v0.3.0 without a version bump;
  resolved **by disposition** — V301 ships unchanged, direct v0.3.0→v1.0.0 migration is declared
  UNSUPPORTED (no v0.3.0 production DB exists), supported upgrade = data-only dump → fresh v1.0.0
  boot → load → verify (proven by rehearsal). Details:
  `docs/releases/v1.0.0-migration-review.md`.
- DEBT-7 (ledger store duplication, deferred), DEBT-8 (webhook route rate limit/body cap,
  accepted for v1), PITR shipped-not-drilled — see `docs/releases/v1.0.0.md` (accepted risks).

## [Unreleased]

### Added — E13 Quality & Security Gates, Block 2 (S4–S5) (2026-09-07)

- **S4/R1 — Evidence-lint** (`scripts/evidence-lint.sh` + CI job on PR + nightly): every
  backtick-quoted `33\d+` run id cited in `docs/epics.md` and the spec acceptance matrices must
  resolve via `gh api` and carry its run number within ±1 line (P6 evidence discipline; job
  output is `file:line: violation`). Grandfathering requires an owner-granted file header,
  never self-served. Historical matrix rows completed with their TRUE run numbers (fetched
  from the API; e5 #125–#132, e8 #146/#147, e10 #118/#119/#123) — 85 ids verified resolving.
- **S4/R2 — Readiness health group**: SQS `get-queue-attributes` (ledger + notifications
  queues) and SNS `get-topic-attributes` (events topic) health indicators through EXISTING
  clients only — the relay's `SnsEventPublisher` now consumes one shared `SnsClient` bean (no
  probe-only second client). Indicators register iff the spine is on (mirror of each client's
  conditional). `management.endpoint.health.group.readiness.include: '*'` makes
  `/actuator/health/readiness` the true deploy gate (blue-green inherits; liveness untouched,
  process-only). ITs: good targets → readiness UP; deliberately-bad ledger queue (property
  override) → readiness DOWN + liveness UP.
- **S4/R3 — Ledger-admin segregation** (`DARGENT_LEDGER_ADMIN_KEY`, default EMPTY =
  404-hidden — the default is the contract): `/v1/ledger/rebuild`, `/v1/ledger/proof`,
  `/v1/ledger/settlements` follow the E9 Q11 ladder: unset → 404-hidden; invalid/revoked → 401
  (filter); valid-but-not-admin → 403 fail-closed; designated admin → 200 audited
  `ledger_admin_*` with the presented key's real identity (never a sentinel). Closes the real
  hole: previously ANY merchant key could settle/rebuild/proof. Audit commands renamed
  (`REBUILD`→`ledger_admin_rebuild`, `SETTLE`→`ledger_admin_settlement`, new
  `ledger_admin_proof`). ITs mirror `OutboxAdminRotationIT` + default-hidden leg; footprint
  tests updated; `ci-proof-daily` exports the job key as admin; compose passes the env through.
- **S5 — Threat model** (`docs/security/threat-model.md`): STRIDE × 6 surfaces, every cell
  cites an existing control (IT/config/script), not intentions. Gaps → **DEBT-8** (no webhook
  rate limit; accepted for v1, NGINX edge when public) + **DEBT-7** registered (owner
  adjudication 2026-09-06: postJournal duplication — register & defer, post-v1.0.0).
- **S5 — `docs/ci-vulnerability-gates.md`** (referenced by design.md §11.1 but never existed):
  threshold/on-failure/suppression/cache policy in one table; design §11.1 truth pass (OWASP is
  a CVSS≥7 gate, not report-only; evidence-lint in the pipeline list); testing-playbook
  scenario 28 carries the `ProductionLockdownIT` pointer.

### Added — E13 Quality & Security Gates, Block 1 (S0–S3) (2026-09-07)

- **Maven wrapper**: `./mvnw` 3.8.7 (exact match with the system Maven; CI migrated to it in the
  same commit, `d4f3d7d`). Closes the design §11.1/runbook `./mvnw` fidelity gap.
- **S0 — SpotBugs 4.9.8.5 (`max`/Medium) + Spotless 3.10.2**, wired into `./mvnw verify`.
  One pre-gate normalize commit (`f80663f`, semantically empty — formatting only, 229 `.java`
  files); real findings fixed, not suppressed: 6× `DM_DEFAULT_ENCODING` → explicit UTF-8; 4
  exclusion classes with rationale + review date (EI_EXPOSE_REP*, DLS — 4.9.8.5 Java-25 record
  false positives, DMI_RANDOM — SecureRandom per key-gen, HRS — X-Request-Id echoed only after
  `^[A-Za-z0-9-]{8,64}$`).
- **S1 — JaCoCo per-module LINE floors** on aggregate (unit+IT) exec: payments **0.70** /
  ledger **0.75** / shared **0.80** / notifications **0.50** / api **0.40** (owner-fixed,
  playbook §5); `scripts/check-coverage.sh` reads floors from the module poms; measured
  0.864 / 0.867 / **1.000** / 0.888 / 0.785. The gate exposed a real hole: `EventEnvelope` had
  zero coverage (shared 0.66 < 0.80) → `EventEnvelopeTest` closes it. Bite-proof: floor 0.99
  goes red (`COVERAGE FAIL payments floor=0.99 measured=0.86`, rc=1), revert → green.
- **S1 — OWASP Dependency-Check 13.0.0**, fail CVSS ≥ 7, keyed NVD (`NVD_API_KEY` repo secret,
  same pattern as the owner's other systems), cached NVD data (delta syncs after the first run
  — measured 90min cold vs 20s warm). **Real finding, real bump**: `tomcat-embed-core 11.0.24`
  (Boot 4.1.1) with 9 CVEs CVSS 7.5–9.8 → `<tomcat.version>11.0.25</tomcat.version>`.
- **S2 — Trivy 2-pass** on both images: pass 1 SARIF (advisory, uploaded), pass 2
  `HIGH,CRITICAL` hard gate. **First real image finding caught on run**: `libcrypto3/libssl3
  3.5.7-r0` (CVE-2026-14456 HIGH) in the alpine runtime → `apk upgrade --no-cache` at image
  build → `3.5.8-r0` locally verified. **SBOM**: CycloneDX per image →
  `sbom-<image>-<sha>.json` workflow artifacts.
- **S3 — CodeQL** (`java`, `security-extended`, push/PR main, action SHA-pinned) + **Dependency
  Review** PR gate (fail on high, SHA-pinned v5.0.0; repo Dependency graph enabled).
- **Latent CI defect found by the gates**: `check-coverage.sh` used `rg` (absent on
  ubuntu-latest → exit 127) → rewritten to grep/sed only; OWASP ran `dependency-check:check`
  per module (could not resolve sibling SNAPSHOTs → aggregate goal instead).
- **Infra**: images' runtime now pulls Alpine security fixes at build; dependency graphs,
  security events, and PR checks configured.

### Added — E12 Deploy & Runtime Smoke, Block 2 (S4–S6) (2026-09-06)

- **N8 — daily ledger proof**: `proof-daily` CI job (cron 03:00 UTC + `workflow_dispatch`) boots the
  full event spine (outbox relay + ledger consumer), pushes one real payment through money path,
  waits for the ledger journal, then proves: `GET /v1/ledger/proof` `ok:true` + DB corroboration
  (Σ DR = Σ CR + balances projection == journal lines). Exit status = the S7 source of truth.
  Local run green end-to-end (txid journaled + proven; Σ DR = Σ CR = 100 cents).
- **N8 — proof-failure counter**: `dargent_ledger_proof_fail_total{scope=balance|projection}`
  incremented on the proof endpoint's failure path (`LedgerMetrics`, both scopes pre-registered at
  0); `ProofResult` carries the failure scope; asserted PRESENT AT 0 in `MetricsScrapeIT` (presence
  assertion, never a seeded failure).
- **N12 — SLO buckets**: `management.metrics.distribution.slo.http.server.requests: 100ms,250ms,1s`
  + `publish-percentile-histogram: true`; `MetricsScrapeIT` asserts the `le="0.25"` bucket line.
- **N7 — log level contract (binding)**: WARN = degraded-but-self-healing, ERROR = needs-a-human;
  spot-check paid (outbox retry-scheduled/purge → WARN, webhook-ignored → INFO).
- **N9 — non-goals with adoption triggers**: tracing/exemplars/tail-sampling deferred by design;
  triggers = second process / inexplicable p95-p99 / multi-host.
- **Runbook truth pass**: release-runbook §3/§4 now describe the real `deploy.sh` behavior
  (management-port readiness, smoke probe per weight bump, last-deploy record, TD-33 gate range).
- **S6 — compose `metrics` profile**: single Prometheus scraping both colors' management ports
  (opt-in convenience, not a contract).
- **Latent defects found and fixed by turning the spine on**: apps/api declared the AWS SNS SDK
  test-scope while wiring the production relay publisher (runtime image missed `SnsClient`
  whenever the relay was enabled → compile scope); LocalStack init subscriptions lacked
  `RawMessageDelivery` (SQS received SNS-wrapped bodies → every event poisoned to the DLQ →
  enforced on subscribe + self-healing set-subscription-attributes on re-run).

### Added — E12 Deploy & Runtime Smoke, Block 1 (S0–S3) (2026-09-06)

- **Deploy artifacts (S0)**: `scripts/deploy.sh` (tag verified → migration-diff gate → new color up →
  management-port healthcheck → stepwise canary 10/30/100 → cutover → old color drained/stopped;
  `--check` prints the plan), `scripts/rollback.sh` (instant weight flip back to the previous color,
  no rebuild), canary weights rendered from `docker/nginx/nginx.conf` into a runtime copy
  (`deploy/runtime/nginx.conf`), forward-only migration gate (fails on `DROP`/`RENAME`/
  `ALTER COLUMN TYPE`/`NOT NULL` additions).
- **Blue-green nginx fix (S1/S2, the money guard)**: compose now binds the nginx runtime conf as a
  DIRECTORY (`./deploy/runtime:/etc/nginx/runtime:ro`) instead of a single file. A file bind-mount
  pins the inode at mount time, so any atomic replace (mv/sed -i/editor) orphans the old inode and
  `nginx -s reload` silently keeps serving stale config — verified live (lift→500, block→503,
  lift→500). Deploy/rollback/chaos now use plain `nginx -s reload`.
- **Runtime-smoke CI job (S2+S3)**: `runtime-smoke` in `.github/workflows/ci.yml` boots the full
  compose stack (api-blue + api-green + postgres + localstack + psp-simulator + nginx), checks
  readiness on the management port, runs `scripts/smoke.sh` (create → idempotent replay → webhook
  CONFIRMED), chaos-legs webhook suppression at nginx and proves the reconciler self-heals the
  payment (zero webhook rows), then SIGTERMs the active color under probe load asserting zero
  connection-refused and a graceful ≤30s exit. Logs archived as a workflow artifact on failure.
- **Webhook intake fail-closed defect (E12 S3 follow-up)**: `POST /webhooks/psp` with a
  non-`application/json` content type (curl `-d` form-urlencoded default) previously tripped Spring's
  `HttpMediaTypeNotSupportedException` → 500 with **no audit row**. The controller no longer restricts
  `consumes` (HMAC *is* the authentication); every rejected payload is now a 4xx — 401
  `invalid_signature`/`signature_expired`, or 400 `invalid_request` for a validly-signed but
  unparseable/non-object/empty body — and the raw bytes are persisted to `webhook_events`
  (opaque non-JSON bodies vaulted as a JSON string so the jsonb column never rejects the audit).
  `WebhookIntakeIT` 13/13 covers both fail-closed cases.
- **Flyway runtime fix**: `spring-boot-starter-flyway` (the Spring Boot 4 starter, not the bare
  `flyway-core`) — the app now runs the 18 schema migrations end to end instead of failing at boot.
- **TD-32 paid**: simulator `GET /v1/cob` resumed on `CANCELED` payments returns honor in the
  reconciler path; `PspGetCobContractIT` + 4× `Reconciler*IT` prove the contract.
- **TD-33 paid — migration gate refined (owner decision 2026-09-06)**: the gate's imprecise pattern
  set (`DROP ` catching `DROP NOT NULL`) was a spec defect. Refined policy: range = LAST-DEPLOY
  (`last-deploy.txt`) + live `flyway_schema_history` cross-check (`since ⊆ db ⊆ tag`); ABORT on
  `DROP TABLE/COLUMN/SCHEMA`, `ALTER COLUMN … TYPE`, `SET NOT NULL`, `RENAME`; ALLOW with log on
  `DROP NOT NULL`, `DROP DEFAULT`; CHECK substitution compares value sets (new ⊇ old passes with
  log, narrowing/parse-fail/new-check-on-existing-table aborts); unknown statement verbs abort.
  `scripts/migration_gate.py` (statement tokenizer + transitional constraint walk) +
  `scripts/test-migration-gate.sh` (widening/destructive/CHECK-narrowing/parse-unknown, CI step).
  Real range accepted: `v0.3.0..HEAD` (`--check` rc=0; V110/V205 + V207 `+RECEIVED` all ALLOW).

### Milestone — E11 Observability ✅ (2026-09-05)

- **E11 ✅ — observability live**: structured ECS JSON logs with end-to-end request correlation
  (intake → outbox relay → ledger ingest), 8 frozen `dargent_*` Prometheus metrics asserted on a real
  scrape (`MetricsScrapeIT`), isolated management port, on-call drill + production lockdown ITs in CI.
  M4 remains ◐ (completes with E12+E13). See `docs/observability.md`.

### Added — E11 Observability (Block 2) (2026-09-05)

- **Frozen Prometheus metrics contract + scrape IT (S4)**: 8 series (names frozen from
  `observability.md` §3) wired through the payments use cases and boot app:
  `dargent_payments_transitions_total{from,to,outcome}` (create/webhook_confirm/reconciler_confirm/
  reconciler_expire/expiry/refund), `dargent_outbox_lag_seconds` (scrape-time gauge over due
  PENDING/EXHAUSTED rows), `dargent_outbox_attempts_total{result=sent|failed|exhausted}`,
  `dargent_dlq_messages{queue}` (60s poller resolving RedrivePolicy → DLQ depth),
  `dargent_reconciler_confirmations_total{outcome=confirm|resurrect}`,
  `dargent_webhook_signature_failures_total{reason=invalid|expired}`,
  `dargent_idempotency_events_total{kind=replayed|conflict|in_flight}`,
  `dargent_refunds_rejected_total{code=not_refundable|exceeds_remaining}`.
  `PaymentsMetrics` (Spring-free holder, payments application) injected into all 6 use cases;
  `OutboxLagGauge` (pulled binder, no scheduler thread); `DlqDepthPoller` (stable gauge holders —
  weak-ref bug fixed pre-merge; per-queue cached `AtomicLong`). `MetricsScrapeIT` boots the real
  app (LocalStack SNS+SQS with DLQ redrive topology, PSP stub with per-txid state, fixed clock,
  background schedulers at huge intervals) and drives every series through the real HTTP surface
  and `runOnce()` house pattern, then scrapes `/actuator/prometheus` on the management port and
  asserts all 8 series with frozen tags, non-zero, lag ≥ 600s, DLQ depth = 1. One env knob only:
  existing `DARGENT_MANAGEMENT_PORT`. Also fixes latent wiring: `SqsEventConsumer`/
  `SqsNotificationConsumer` now take `@Qualifier` clients (relay-enabled + consumer-enabled boots
  previously had an ambiguous `SqsClient` bean pair — production-boot breaker found by the IT).

### Added — E9 Delivery Hardening (Block 1+2) (2026-09-04)

- **Bounded outbox delivery — EXHAUSTED contract (S1)**: outbox rows failing publish after
  `DARGENT_RELAY_MAX_ATTEMPTS` (default 3) are marked `EXHAUSTED` via conditional `UPDATE
  ... WHERE status='PENDING'`. Ladder timings frozen (30s / 2m / 5m ceiling). `OutboxExhaustionIT`
  asserts forced failure ×3 → EXHAUSTED, never re-claimed by relay. Unit matrix covers 1→30s,
  2→2m, 3→EXHAUSTED, lost-race no-op.
- **Audited requeue endpoint (S2)**: `POST /v1/outbox/{id}/requeue` admin-gated (env
  `DARGENT_OUTBOX_ADMIN_KEY`); conditional `EXHAUSTED→PENDING` with `attempt_count=0`,
  `next_attempt_at=now()`, audited as `outbox_requeued` with real API-key principal. Ladder:
  env absent→404-hidden, no/unknown/revoked key→401, valid≠admin→403, valid==admin→200. Q11
  rotation-window 403: while env points at revoked predecessor, active successor presents→403
  (validation first); revoked predecessor presents→401. `OutboxRequeueIT` + `OutboxAdminRotationIT`.
- **Republish tool with deterministic salted IDs (S3)**: `POST /v1/outbox/republish` body
  `{from, to, types?}` (≤30d window, ≤500 rows). For each matched SENT row: inserts new PENDING
  row with `eventId={original}-r{n}` where n is the replay ordinal — **re-running the same
  republish produces identical new IDs**, making the tool itself idempotent at consumers.
  Originals untouched (stay SENT). Admin-gated, audited as `outbox_republished` with window
  marker. `OutboxRepublishIT` + `OutboxRepublishRotationIT` cover basic, re-run (scenario 20
  foundation), window bounds, type filter, empty window, auth ladder.
- **Scenario-20 no-double-journaling guard (S4)**: ledger consumer (`EventIngestionUseCase`)
  checks `store.hasPostedJournalForTxid(txid)` before posting `payment.confirmed`. If a POSTED
  journal already exists for the txid (republished event), marks event POSTED with note
  `Republished — already journaled` and **skips journal creation**. Journal count and balances
  unchanged. Core logic in `EventIngestionUseCase.processMessage()` +
  `JdbcLedgerStore.hasPostedJournalForTxid()`.
- **DLQ recipes doc (S5)**: `docs/dlq-recipes.md` with inspection, peeking, common failure
  patterns (invalid payload, insufficient balance, relay timeout, webhook failure), requeue
  procedures (republish via admin endpoint preferred, manual fallback), purge, forensics SQL
  queries (IGNORED/REJECTED events, double-journal check, stale outbox, admin audit trail),
  and Prometheus alerting thresholds.
- **M3 ✅ Suffering complete**: Refunds + expiration + resurrection + reconciler + D+1 settlement
  + **DLQ + backoff + EXHAUSTED + requeue + republish** now fully implemented. All catalog
  scenarios 6–12, 19–20, 23–24, 26–27 green.

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