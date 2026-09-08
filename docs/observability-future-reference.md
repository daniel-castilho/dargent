# Observability future reference — exemplars, tail sampling, composite (curated from 4th external analysis, 2026-09-05)

**Status: DEFERRED-BY-DESIGN.** Tracing is a ratified non-goal (design.md §9: monolith + correlation
ids; stretch when services get extracted). Exemplars WITHOUT tracing = dead config (the analysis's own
conclusion). This file is the adoption pack for the day a trigger fires.

**Adoption triggers (any one opens the tracing epic):**
1. Ledger or relay extracted into a second process.
2. p95/p99 inexplicable from logs + metrics (the profiling step).
3. Multi-host infrastructure.

**Hard prerequisite already satisfied:** correlation ids cross the async boundary (request_id + txid in
the outbox envelope — E5/E11). "Tail sampling não conserta correlação quebrada" — pipelines only choose
between traces that are already whole.

## Exemplars (Boot 4 / Micrometer / Prometheus — VERIFY artifact names at adoption; zero-from-memory)

- Prerequisites (all three together): (1) trace context on the recording thread (Micrometer Tracing →
  OTel, span sampled); (2) classic histogram (`percentiles-histogram: true` / `publishPercentileHistogram()`);
  (3) OpenMetrics-negotiated scrape + Prometheus `--enable-feature=exemplar-storage`.
- Boot 4 claim to verify: `spring-boot-starter-opentelemetry` pulls OTel bridge + OTLP registry; the new
  `micrometer-registry-prometheus` registers the SpanContext automatically when tracing is present.
- Domain timers via `Observation` (NOT bare `registry.timer().record()` outside scope) — sampler must
  see the span or the exemplar is empty. `exemplarsOnAllMetricTypes=true` config knob. Don't double-register
  observation handlers (duplicate series).
- IT: sample 1.0 forced; POST traced request → scrape management port with
  `Accept: application/openmetrics-text; version=1.0.0` → assert `# {span_id=...,trace_id=...}` present.
  `text/plain` omits exemplars — the classic false negative.
- Gauges (outbox_lag) never carry exemplars — correlate lag via logs (txid + request_id).
- No `txid` tags on metrics, ever (cardinality kills Prometheus); identity lives in trace + log.

## Tail sampling (collector) — "declarar o que é evidência"

- Head sampling 100% on the API (money path); the CUT happens in the collector. Head 1% + tail error
  policy = blind tail.
- `decision_wait` knack for Dargent: 15s (journal happens in the consumer AFTER the webhook confirm;
  shorter waits orphan the ingest span). Buffer sizing: `num_traces`, `expected_new_traces_per_sec`,
  `decision_cache` ≥ 2× typical consumer delay. Single collector per trace_id (hash-based LB, never
  round-robin) or decisions go blind and latency policies undercount.
- Keep-the-pain policy set (OR, before composite): drop probes (actuator/health/prometheus spans) →
  keep ERROR spans → keep money outcomes by attribute (`hmac_fail`, `balance_unavailable`,
  `idempotency_conflict`, `proof_fail` — 409-class business failures are span-OK and need the attribute
  policy) → keep SLO breaches ANDed with surface (`payments|webhook` @ 250ms; `outbox|ledger` @ 1000ms —
  thresholds from OUR slos.md, never the 5s tutorial default) → probabilistic 5% baseline.
- Map: 409 balance → `balance_unavailable`; 409 idempotency → `idempotency_conflict` (low-cardinality
  span attrs: `dargent.surface`, `dargent.outcome`).
- Metrics pipeline runs OUTSIDE the tail path (histograms need the whole population or the p95 lies and
  exemplars point at a biased population). Spanmetrics connector before-tail for faithful RED.
- Drill to prove the policy: force slow POST > 250ms → trace MUST exist; force bad HMAC → must exist;
  happy 80ms → ~baseline fraction only. All three failing = policy wrong or trace_id didn't cross.

## Composite (quota-based) — the fuse with a priority queue

- Adopt only when volume justifies (OR-free set overflowing Tempo/disk): `max_total_spans_per_second`
  is SPANS not traces (≈15 spans per money request); `policy_order` + `rate_allocation` slices; first
  match spends its slice, overflow falls to the next, nobody → NotSampled. Percentages need not sum to
  100 — leave an `always_sample` (or probabilistic) baseline last or the remainder dies.
- Drop-probe stays OUTSIDE the composite (semantic priority; 1-span health is cheap and would eat quota).
- Example split at 2000 sps: errors 35 / money-reject 20 / slow-api 25 / slow-async 10 / baseline 10.
- `recordpolicy` feature gate stamps the winning policy on the span — audit who ate the quota.
- Classic mistakes: rate_allocation name ≠ sub_policy name (silent zero slice); drop inside composite;
  two collectors without trace_id hash (doubled quota, split decisions); latency upper bound (stateful
  decisions); sampling metrics with the same criteria as traces.

## Order when the trigger fires

1. OTel starter + sample 1.0 (dev/ci profiles); histogram on `http.server.requests` + 3 domain timers
   (SLO buckets from slos.md: 100/250/1000ms etc.).
2. OpenMetrics exemplar IT (trace_id in scrape).
3. Single collector in compose; Tempo; Prometheus exemplar-storage scraping :9090 (management port).
4. Keep-the-pain tail policies with OUR SLO thresholds; decision cache sized; drill the three probes.
5. Composite + recordpolicy + real spans/s numbers.
6. Prod-like head sampling < 1 + tail in collector only after 1–5 are stable.

**Related small candidates (dispositions in register):** N7 log-level semantics; N8
`ledger_proof_fail_total` + daily proof job; N9 observability.md deferral note; N10 synthetic probe
(E12); N11 retention split (audit 1y / debug 14d); N12 histogram SLO buckets (earliest cheap win — can
land without tracing, just distribution config + bucket assertions).
