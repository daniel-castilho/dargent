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
  - `WARN` is reserved for degraded-but-self-healing paths (fail-open, retry scheduled, reconciler compensating).

## 3. Metrics (Micrometer → Prometheus at `/actuator/prometheus`)

**Status: live (E11).** All 8 series below are wired end-to-end and asserted — with their frozen tag
vocabularies and non-zero values — on a real `/actuator/prometheus` scrape of a prod-profile boot by
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
| Confirmed but ledger unbalanced | balance proof job failure | Freeze deploys; run triage procedure (release-runbook §7) |
| DLQ depth > 0 | `dargent_dlq_messages{queue}` | Inspect message, fix cause, requeue per runbook |

## 7. Explicit non-goals

- Distributed tracing (added only if/when the ledger is extracted; correlation ids keep that door open).
- Log aggregation stack on-prem (docker json-file + host retention is enough for v1; shipping to a collector is a stretch).
