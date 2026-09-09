# Observability

Logs first (they answer "what happened to this payment"), metrics second (they answer "is the architecture
healthy"), distributed tracing deliberately out (a modular monolith with correlation ids doesn't need it yet).

---

## 1. Correlation

`RequestCorrelationFilter` on every request:

- Accepts inbound `X-Request-Id` (validated: safe charset, ≤ 64 chars); malformed values are replaced.
- Generates `X-Request-Id` when absent; **echoes it in the response header**.
- Puts `request_id` into the MDC for every log line; propagates it into outbox events (consumers log with it).

## 2. Logging

- Format: Boot 4 built-in structured JSON (ECS) in prod-like profiles; readable console pattern in dev.
  Zero extra dependencies.
- Required MDC fields on every line: `request_id`; plus `payment_id` (or `aggregate_id`) and `merchant_id`
  where the context has them.
- Rules (enforced by review, spot-checked in the smoke):
  - Log **outcomes and state transitions**, not payloads. Raw webhook bodies live in `webhook_events.payload_raw`
    — log the id, not the blob.
  - Never log API keys, HMAC signatures, bearer tokens, or QR payloads.
  - No log-and-rethrow duplication; pick the layer that reports.
- **Level contract (N7, binding):** `WARN` = degraded-but-self-healing — a retry is scheduled, backoff is
  engaged, the reconciler will compensate, the redrive ladder owns the message. Nobody needs to wake up;
  if the condition persists past its designed window it escalates (metrics will show it). `ERROR` = needs
  a human — poison message heading to the DLQ after the final attempt, outbox `EXHAUSTED`, ledger proof
  failure (`ok:false` from `/v1/ledger/proof`), anything that already lost data or will lose data if no
  one acts. A stack trace without an owner-action is a defect; a WARN that never escalates is a defect.

## 3. Metrics (Micrometer → Prometheus at `/actuator/prometheus`)

**Status: live (E11; proof counter + SLO buckets added in E12; webhook abuse rejections added in E15 S1).** All 10
series below are wired end-to-end and asserted — with their frozen tag vocabularies and non-zero values (proof-fail
and webhook-rejections asserted PRESENT AT 0) — on a real `/actuator/prometheus` scrape of a prod-profile boot by
`MetricsScrapeIT` (CI). Names are FROZEN: renaming any series is a contract break.

| Metric | Type | Labels | Question it answers |
|---|---|---|---|
| `dargent_payments_transitions_total` | counter | `from`, `to`, `outcome` | Are payments flowing? How many resurrections/failures? |
| `dargent_outbox_lag_seconds` | gauge | — | **The architecture metric**: age of the oldest unpublished outbox event |
| `dargent_outbox_attempts_total` | counter | `result` (sent, failed, exhausted) | Is the relay healthy? How much backoff pressure? |
| `dargent_dlq_messages` | gauge | `queue` | Poison messages sitting in a DLQ right now |
| `dargent_reconciler_confirmations_total` | counter | `outcome` | How many webhooks did we effectively lose? (reconciler confirming = lost webhook) |
| `dargent_webhook_signature_failures_total` | counter | `reason` (invalid, expired) | Attack noise / clock drift |
| `dargent_idempotency_events_total` | counter | `kind` (replayed, conflict, in_flight) | Client retry behavior pressure |
| `dargent_refunds_rejected_total` | counter | `code` | Money-guard trips (exceeds remaining, not refundable) |
| `dargent_ledger_proof_fail_total` | counter | `scope` (balance, projection) | Ledger proof failures (N8) — 0 is the only good value; a non-zero page is a freeze-deploys moment |
| `dargent_webhook_rejections_total` | counter | `reason` (rate_limited, body_too_large) | Webhook abuse-control trips (E15 S1, DEBT-8) — 429/413 verdicts; feeds the abuse alert rule |
| `dargent_cache_hits_total` | counter | `path` (idempotency-replay) | Replay-cache reads served from Redis (M5 S2) — is the cache paying for itself? |
| `dargent_cache_misses_total` | counter | `path` (idempotency-replay) | Cache reads that fell to the DB (first-touch / TTL expiry) |
| `dargent_cache_failopen_total` | counter | `path` (idempotency-replay) | Cache failures absorbed (Redis down/unhealthy) — the cache is fail-open by contract; sustained growth = Redis needs attention, the money path does NOT |

Naming follows Micrometer conventions (dots, lower-case); Prometheus exposition renders `dargent.*` as `dargent_*`.

### Webhook limiter posture (E16 S4 — declared, per-instance by design)

- **Scope key:** first hop of `X-Forwarded-For` (set at the NGINX edge) falling back to
  `getRemoteAddr()` — the real caller's IP, not the proxy's. One bucket per caller IP **per API
  instance** (in-heap `ConcurrentHashMap`; no shared store — zero-dependency was the E15 S1
  design decision).
- **Quota math per replica:** with defaults (capacity 100, refill 0.5 rps) ONE instance admits
  a single caller at 100 burst + 0.5 sustained; NGINX splits traffic by canary weight
  (`deploy.sh --canary 10,30,100`), so **two hot colors ≈ double quota** for the same caller
  (each color keeps its own bucket). Steady state (100/100) = 2× the printed defaults; a
  canary window (10/90) = 1.1×. The honest k6 run (E16 S3) shows exactly this behavior at the
  fleet level: the PSP burst exhausts the per-IP bucket and confirmations shift to the
  reconciler — the control working as designed.
- **When a shared-store limiter becomes warranted:** the moment a single legitimate caller
  needs a fleet-wide quota ABOVE what `refill × replica-count` allows, or abuse must be
  contained to ONE budget across replicas. That is a shared in-memory/Redis limiter —
  **deferred to M5 with rationale** (M5 brings Redis; `tasks/m5-scoping.md` D3 — "cache is an
  optimization, not a dependency" applies to the limiter store the same way). No silent
  carry-over: this paragraph IS the disposition.

### Replay cache posture (M5 S2 — one path, opt-in, fail-open)

- **What caches:** the idempotent-replay lookup only (D3 adjudication — the ONE hot read path).
  Completed snapshots (`idempotency_keys`) are immutable and never deleted once completed, so a
  TTL-bounded cache can never contradict the DB; IN_FLIGHT rows are never cached (425 arbitration
  stays DB-owned). Cached records carry the request fingerprint, so 409-conflict semantics are
  identical from cache or DB.
- **Fail-open by contract:** Redis down/unhealthy → DB fallback, correctness unchanged, the
  failure counted in `dargent_cache_failopen_total` and absorbed — never surfaced to the money
  path. Command timeout capped at 1s so a dead cache cannot lag requests. Proven by
  `ReplayCacheIT` (Redis container stopped mid-test).
- **Lifecycle:** default OFF (`DARGENT_CACHE_REDIS_ENABLED=false` → zero Redis beans, DB direct,
  Boot's Redis auto-config excluded). ON: enable + `DARGENT_CACHE_REDIS_URI` + TTL via
  `DARGENT_CACHE_REDIS_TTL` (default 5m; entries are also evicted when a key row is deleted).
  Compose: `redis` service, opt-in profile `cache`.
- **Eviction vs the revoked-key rule:** a completed key row is never deleted in the current
  surface (only IN_FLIGHT rows are, on PSP exhaustion) — so "revoked key must never outlive TTL"
  maps to evict-on-delete + TTL bound; both are IT-pinned.
- **Health:** deliberately NOT in the health model — the cache is an optimization; a down cache
  must never fail readiness (fail-open posture above).

## 4. Health model

- `GET /actuator/health/liveness` — JVM alive. Public on the management port.
- `GET /actuator/health/readiness` — gated on **Postgres reachable** and **LocalStack SNS/SQS reachable**;
  the blue-green readiness gate and Docker healthchecks consume this.
- Actuator exposure in prod: health-only, `show-details: never`, management port isolated from the business
  listener — **proven by an integration test** that boots the prod profile (lockdown IT), not by documentation.
- Swagger/api-docs: enabled in dev, absent from the prod profile (also covered by the lockdown IT).

## 5. Scraping & dashboards

- Prometheus scrapes `api-blue`/`api-green` management ports; job names carry the fleet color.
- **Alert rules (E15 S2):** `docker/prometheus/rules/alert-rules.yml`, loaded by the `metrics`
  profile Prometheus via `rule_files`. Every rule is **unit-tested firing AND quiet with
  `promtool test rules`** (`docker/prometheus/rules-tests/alert-rules.test.yml`) and that test **runs
  in CI on every push** — an untested or mis-evaluated rule is a red build. Thresholds are anchored
  to [`slos.md`](slos.md); each rule's annotations carry the exact release-runbook §7 response line.
- Alert set (name — trigger — severity — anchors):
  | Rule | Trigger | Severity | Runbook anchor |
  |---|---|---|---|
  | `DWARF_LEDGER_PROOF_FAIL` | any `dargent_ledger_proof_fail_total` page (S7 invariant) | critical | §7 "Ledger proof failed" — freeze deploys |
  | `DWARF_OUTBOX_LAG_ABOVE_SLO` | `dargent_outbox_lag_seconds > 300` sustained 5 min (S6) | warning | §7 "Outbox lag climbing / EXHAUSTED rows" |
  | `DWARF_DLQ_DEPTH` | `dargent_dlq_messages > 0` sustained 5 min | warning | §7 "DLQ depth > 0" |
  | `DWARF_WEBHOOK_SIGNATURE_STORM` | ≥ 10 HMAC failures in 15 min | warning | §7 "Webhooks rejected en masse" |
  | `DWARF_OUTBOX_ROWS_EXHAUSTED` | any row `EXHAUSTED` in 15 min | warning | §7 "Outbox lag climbing / EXHAUSTED rows" |
  | `DWARF_WEBHOOK_RATE_LIMITED_STORM` | ≥ 100 × 429 in 15 min (E15 S1 control) | warning | §7 "Webhook 429/413 storm" |
  | `DWARF_WEBHOOK_BODY_TOO_LARGE_STORM` | ≥ 20 × 413 in 15 min (E15 S1 control) | warning | §7 "Webhook 429/413 storm" |
- **Alertmanager (E16 S1):** the `metrics` profile also runs Alertmanager (`prom/alertmanager:v0.27.0`)
  + a **webhook-logger stub** receiver (`docker/alertmanager/` — one-file stdlib HTTP sink that logs
  every routed alert group; NO pager, NO external sink by fence). Prometheus `alerting:` points at it.
  Routes: `critical` (repeat 5 m) and `warning` (repeat 4 h), both → the stub. The config is
  **validated by `amtool check-config` in CI** on every push (additions-only beside promtool — a
  broken route tree is a red build). To wire a real receiver later: edit the `webhook_configs`
  url in `docker/alertmanager/alertmanager.yml` (or add email/slack/pager integrations), re-run
  amtool locally, ship. Wiring evidence (2026-09-08, local): alert posted to the Alertmanager API
  was routed and logged by the stub —
  `{"stub": "webhook-logger", "alert": "DWARF_LEDGER_PROOF_FAIL", "severity": "critical", …}`.
- Grafana is optional/stretch; until then, the runbook's "quick diagnosis" table + `curl /actuator/prometheus | grep`
  recipes cover on-call needs.
- Panels that matter, in order: outbox lag, DLQ depth, payments transitions (stacked), reconciler confirmations,
  webhook signature failures.

## 6. Quick diagnosis (symptom → check → action)

| Symptom | Check | Action |
|---|---|---|
| Payments stuck `PENDING` | `dargent_outbox_lag_seconds` high or relay logs | Runbook §Incidents (relay down / SNS down) |
| Merchant reports "paid but pending" | `dargent_reconciler_confirmations_total` not moving; webhook logs | Check webhook intake errors; run reconciler manually |
| Sudden 401 storm on API | `dargent_webhook_signature_failures_total` / API auth logs | Key rotation or clock drift on caller |
| Confirmed but ledger unbalanced | balance proof job failure | Freeze deploys; run triage procedure (release-runbook §7). The `proof-daily` CI job (03:00 UTC) is the standing check; `dargent_ledger_proof_fail_total{scope}` tells you which side broke (balance = ΣDR≠ΣCR, projection = balances≠lines) |
| DLQ depth > 0 | `dargent_dlq_messages{queue}` | Inspect message, fix cause, requeue per runbook |

## 7. Explicit non-goals

- **Deferred by design — distributed tracing, exemplars, tail sampling.** Adoption triggers (any one
  makes the deferral wrong): (a) the ledger or the relay is extracted as a second process; (b) p95/p99
  becomes inexplicable from logs+metrics alone; (c) the fleet spans multiple hosts. The prerequisite —
  correlation of `request_id` + `txid` crossing the outbox boundary — is already enforced by IT, so
  adopting tracing later is additive, not a rewrite.
- Log aggregation stack on-prem (docker json-file + host retention is enough for v1; shipping to a collector is a stretch).
