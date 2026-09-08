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

Naming follows Micrometer conventions (dots, lower-case); Prometheus exposition renders `dargent.*` as `dargent_*`.

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
