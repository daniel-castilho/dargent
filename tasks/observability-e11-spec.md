# E11 Spec — Observability (exact contracts)

Authority: design.md §9 > observability.md (frozen names) > slos.md > this file (implementation
precision only). Where this file and observability.md disagree, observability.md wins (STOP/P5).

## §1 Scope

Logs (structured ECS), correlation proofs, on-call drill, production lockdown, the 8 metrics with
exposition. Out of scope: scraping topology, dashboards, alerting, tracing, async appenders (P2/P4).

## §2 Log contract

- Prod-like profile only: `logging.structured.format.console=ecs` (Boot 4 built-in — zero deps).
  Dev/console profile unchanged (human-readable).
- Fields on every structured line: `request_id` (always — MDC via existing `RequestIdFilter`);
  `txid`/`aggregate_id` and `merchant_id` wherever the executing context holds them. ECS field naming
  follows Boot's ECS formatter; house correlation keys ride MDC keys unchanged.
- **Scrubbing invariant:** raw API keys, `Authorization` values, and the DB password appear in NO
  emitted line. Proven, not promised (IT leg).
- Retention/ship: unchanged (Docker json-file) — observability.md §2/§7.

## §3 Management port + lockdown contract

- `management.server.port=${DARGENT_MANAGEMENT_PORT:9090}`; exposure `health,info,prometheus`.
- Main port: SecurityConfig drops actuator matchers; `anyRequest().denyAll()` + authenticated `/v1/**`
  semantics unchanged. `denyAll` ⇒ actuator on 8080 fails closed.
- Dockerfile HEALTHCHECK → `http://localhost:9090/actuator/health`; compose healthchecks likewise;
  9090 NOT published; NGINX untouched.
- Lockdown assertions (prod-like): swagger/api-docs absent (404), health has no details, business
  endpoints enforce key + tenant-of-credential.

## §4.1 Environment contract (new names — complete list; defaults are contract)

| Env | Default | Meaning |
|---|---|---|
| `DARGENT_MANAGEMENT_PORT` | `9090` | Isolated actuator port (health/info/prometheus); never published |

Exactly one new env. No feature flags: metrics and structured logs are profile-driven, always on in
prod-like. Zero migrations; zero ledger/notifications prod-code changes EXCEPT none — counters live in
payments/api only (outbox lag binder + DLQ poller in payments; webhook counter in api; transitions/
idempotency/refunds counters in payments use cases). If any step feels a ledger/notifications edit
"would be cleaner": STOP/P4 — restructure via port/adapter or api-side composition.

## §5 Metrics contract (names/tags FROZEN from observability.md §3)

| Metric | Type | Tags | Wire point (no new branches) |
|---|---|---|---|
| `dargent_payments_transitions_total` | counter | `from`,`to`,`outcome` | payment state transitions (use case level) |
| `dargent_outbox_lag_seconds` | gauge | — | binder: `max(now - next_attempt_at) over PENDING/EXHAUSTED due` via JdbcClient |
| `dargent_outbox_attempts_total` | counter | `result`=`sent`/`failed`/`exhausted` | relay mark paths (E9 semantics) |
| `dargent_dlq_messages` | gauge | `queue` | scheduled SQS GetQueueAttributes poller (payments adapter) |
| `dargent_reconciler_confirmations_total` | counter | `outcome` | ReconciliationUseCase confirm leg |
| `dargent_webhook_signature_failures_total` | counter | `reason`=`invalid`/`expired` | webhook intake rejection legs |
| `dargent_idempotency_events_total` | counter | `kind`=`replayed`/`conflict`/`in_flight` | CreatePaymentUseCase outcomes |
| `dargent_refunds_rejected_total` | counter | `code` | RefundPaymentUseCase rejection legs |

Naming per Micrometer (dots, lowercase); Prometheus renders `dargent_*`. Registry:
`micrometer-registry-prometheus` (Boot-managed). MeterRegistry injected via composition configs —
domain stays meter-free where a port/adapter can carry it; where the counter IS domain-outcome
instrumentation, use-case-level injection is sanctioned (api module composes).

## §6 Integration tests (names locked; case-sensitive `*IT.java`; Clock; zero sleeps)

1. `ManagementPortIT` — §3 assertions.
2. `JsonLogCorrelationIT` — §2: ECS parse, correlation fields, end-to-end request_id, scrubbing legs.
3. `OnCallTxidDrillIT` — pendular txid trail via emitted logs; budget via Clock accounting.
4. `ProductionLockdownIT` — §3 lockdown assertions (prod-like).
5. `MetricsScrapeIT` — all 8 series present with tags after seeded actions; lag gauge monotonicity
   sanity (seeded old row ⇒ lag > 0); DLQ gauge reflects LocalStack queue depth.

## §7 Hygiene gates

Standing set + `grep -rn "MeterRegistry" modules/ledger modules/notifications` returns nothing (rule
§4.1) + `grep -rn "dargent_" docs/observability.md` count = `MetricsScrapeIT` asserted series count —
pasted with commit ids. DOD §2 self-audit pasted in the handoff.

## §10 Acceptance matrix (skeleton — executor fills with pairs)

| Item | Deliverable | Test / Evidence | CI Run | Status |
|---|---|---|---|---|
| S0 | port+registry+ECS+healthchecks | `ManagementPortIT` | pair | ◻ |
| S1 | JSON logs + scrubbing | `JsonLogCorrelationIT` | pair | ◻ |
| S2 | on-call drill | `OnCallTxidDrillIT` + runbook | pair | ◻ |
| S3 | lockdown | `ProductionLockdownIT` | pair | ◻ |
| S4 | 8 metrics | `MetricsScrapeIT` | pair | ◻ |
| S5 | docs truth + E11 ✅ (M4 ◐) + citation | epics diff | pair | ◻ |
