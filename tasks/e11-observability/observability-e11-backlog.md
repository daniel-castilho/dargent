# E11 Backlog — Observability

Epic goal: every guarantee in `docs/observability.md` is true of the running binary and proven in CI.
Anchor: design.md §9 · observability.md (frozen metric names) · slos.md S5/S6 · DOD `docs/handoff-dod.md`.

```
E11 Observability (M4)
├── S0  Foundation: management port, registry dep, ECS logging, healthcheck updates   [Block 1]
├── S1  JSON-log proofs: correlation fields + scrubbing legs                          [Block 1]
├── S2  On-call drill IT: pendular txid findable in ≤2 min (mechanized)               [Block 1]
├── S3  Production lockdown IT: swagger absent, actuator isolated, key mandatory      [Block 1]
├── S4  The 8 dargent_* metrics wired + scrape proof                                  [Block 2]
└── S5  Docs truth pass + TD-22 resolution + E11 ✅ flip + citation                    [Block 2]
```

### S0 — Foundation (Block 1)
- `micrometer-registry-prometheus` dependency (Boot-managed version); `management.server.port`
  from `DARGENT_MANAGEMENT_PORT` (default 9090); exposure `health,info,prometheus` ON THE MANAGEMENT
  PORT ONLY; SecurityConfig loses the main-port actuator matchers (denyAll stands); Dockerfile
  HEALTHCHECK + compose healthchecks curl 9090; compose does NOT publish 9090.
- **Accept:** `ManagementPortIT` — main port: `/actuator/**` denied (401/403/404 family, asserted
  exactly); management port: health UP + `/actuator/prometheus` serves exposition text.

### S1 — JSON-log proofs (Block 1)
- Prod-like profile: Boot structured logging, ECS format, console. Fields: `request_id` always;
  `txid`/`aggregate_id`, `merchant_id` where context exists. MDC already flows (E3 filter — verified).
- **Accept:** `JsonLogCorrelationIT` — (a) webhook intake emits parseable JSON lines carrying the
  echoed `X-Request-Id` + txid; (b) relay/ingest log lines carry the same request_id (correlation
  end-to-end); (c) **scrubbing legs**: Authorization header value, raw API key, DB password appear in
  NO emitted line (rider N4).

### S2 — On-call drill (Block 1)
- The maturity-carry drill, mechanized: seed a pendular payment (PENDING with retries due), then run
  the operator path a human would run — filter emitted logs by txid, then by request_id — and assert
  the trail answers: current status, last transition, next attempt time. Budget asserted via injected
  Clock accounting (≤2 min operator time modeled), never wall-clock sleeps.
- **Accept:** `OnCallTxidDrillIT` green in CI; the drill's steps are copy-pasteable as a runbook
  snippet (`docs/runbooks/on-call-diagnosis.md`, ≤30 lines).

### S3 — Production lockdown (Block 1)
- **Accept:** `ProductionLockdownIT` (prod-like profile) — swagger/api-docs endpoints absent;
  actuator served ONLY on the management port; health `show-details: never`; business endpoints
  reject no-key (401) and non-merchant-key (403/401 per E3 §3.7 semantics).

### S4 — Metrics (Block 2)
- All 8 `dargent_*` per the frozen table (spec §5): counters at existing decision points (no new
  branches), lag gauge via `JdbcClient` binder, DLQ gauge via scheduled SQS `GetQueueAttributes`
  (payments adapter, existing client, LocalStack-compatible).
- **Accept:** `MetricsScrapeIT` — after seeded actions (create/idempotent-replay/conflict, webhook
  sig failure, relay sent/failed/exhausted, reconciler confirm, refund rejected, DLQ seed), the
  `/actuator/prometheus` text contains each `dargent_*` series with expected tags and nonzero values.

### S5 — Docs truth pass + flips (Block 2)
- README TD-22 row becomes TRUE (logs + metrics live — present tense WITH proof); slos.md S5/S6
  sources now live; observability.md §5 marked live; CHANGELOG; design deltas (management port).
- **Accept:** flip = **E11 ✅** in `docs/epics.md`, **M4 ◐ preserved** ("completes with E12+E13"),
  last content commit; then exactly one citation commit citing a run whose tree IS the flip.
