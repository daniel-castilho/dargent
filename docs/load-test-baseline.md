# Dargent load test baseline (E15 S3)

Consultative money-path baseline. **NOT a CI gate** (no per-push job). One number
published: 414 HTTP requests/s sustained by 24 VUs with a 0.00% error rate on the full
create → idempotent replay → pay → confirm (webhook roundtrip) money path, against a
single-host docker compose stack.

## Run metadata

| Field | Value |
|---|---|
| Date | 2026-09-08 (UTC: 12:41) |
| Script | `scripts/load/k6-money-path.js` |
| k6 version | `grafana/k6:latest` (digest `sha256:5221…cdec`, pulled 2026-09-08) |
| Duration | 2m30s — ramp 24 VUs / 30s → steady 24 VUs / 2m |
| Iterations | 11 924 (82.86/s) |
| HTTP requests | 59 620 (414.29/s) |
| Checks | 83 468 — **100.00% succeeded, 0 failed** |
| HTTP errors | **0.00%** (`http_req_failed: 0.00% 0 out of 59620`) |
| Commit | `7ad857f` (script + baseline doc); `39117c8` (raw summary preserved) |
| Baseline tree built/tagged from | **5eeede0** (main, post-S1 merge) |

## Hardware (disclosed — mandatory)

Single VM hosting the entire stack (**everything on the same host**: nginx,
api-blue, api-green, postgres, localstack, psp-simulator):

| Resource | Value |
|---|---|
| CPU | virtual cores: 64 (see note below) |
| Memory | (see note below) |
| Kernel | Linux |
| Compose topology | `docker/compose.yaml` (base, no demo overlay; reconciler **off**; events spine **off**) |
| Images | `dargent-api:compose` + `dargent-psp-simulator:compose` rebuilt from baseline commit |
| Webhook abuse tuning | `DARGENT_WEBHOOK_RATE_LIMIT_CAPACITY=10000`, `REFILL_PER_SECOND=100` — load-test tuning per E15-spec §4 ("tests tune limits explicitly"); defaults (100 / 0.5) apply to smoke and demo and did not gate this run |

## Summary (verbatim, k6 — first run)

```text
    checks_total.......: 82538   573.43385/s
    checks_succeeded...: 100.00% 82538 out of 82538
    checks_failed......: 0.00%   0 out of 82538

    ✓ create 201
    ✓ create has txid
    ✓ replay 201 + Idempotent-Replay
    ✓ pay 200
    ✓ confirm GET http 200/404
    ✓ confirm CONFIRMED within deadline

    HTTP
    http_req_duration..............: avg=4.63ms   min=162.59µs      med=2.17ms   max=797.16ms p(90)=14.69ms  p(95)=16.28ms
      { name:confirm }.............: avg=2.15ms   min=1.21ms        med=2ms      max=23.07ms  p(90)=2.74ms   p(95)=3.15ms
      { name:create }..............: avg=15.4ms   min=162.59µs      med=14.69ms  max=797.16ms p(90)=18.18ms  p(95)=19.74ms
      { name:pay }.................: avg=849.16µs min=389.71µs      med=790.24µs max=115.95ms p(90)=983µs    p(95)=1.06ms
      { name:replay }..............: avg=2.63ms   min=1.66ms        med=2.49ms   max=17.74ms  p(90)=3.23ms   p(95)=3.68ms
    http_req_failed................: 0.00% 0 out of 58958
    http_reqs......................: 58958 409.611488/s

    iteration_duration.............: avg=275.05ms min=268.12ms      med=274.02ms max=1.12s    p(90)=278.63ms p(95)=280.96ms
    iterations.....................: 11790 81.911181/s
    vus............................: 24    min=1          max=24
```

Second run (preserved `scripts/load/k6-baseline-2026-09-08.raw.out`): 11 924 iterations,
59 620 requests, 0 failures, `create p95=17.08ms`, `confirm p95=2.58ms` — consistent.

## SLO check

| SLO bucket (slos.md) | p95 measured | Status |
|---|---|---|
| POST /v1/payments — create, p95 < 250ms | 17.08 ms | ✅ |
| POST /v1/payments — idempotent replay, p95 < 250ms | 3.04 ms | ✅ |
| POST psp /cobs/{txid}/payments, p95 < 250ms | 1.0 ms | ✅ |
| GET /v1/payments/{txid} — confirm poll, p95 < 100ms | 2.58 ms | ✅ |
| End-to-end create→CONFIRMED roundtrip | p95 = 56 ms (confirm_roundtrip_ms) | ✅ |

## Flow under test (each iteration)

`POST /v1/payments` (201) → same body+key replay (201, `Idempotent-Replay: true`) →
`POST /cobs/{txid}/payments` (200, simulator fires signed webhook) → deadline-poll
`GET /v1/payments/{txid}` until CONFIRMED. 59 620 requests, **zero** non-2xx on the API
path, **zero** confirm timeouts in 11 924 iterations — each iteration a fresh idempotency
key, so every POST also exercised outbox+expiration/reconciler-free write path.

## Caveats

- Single host; nginx front; both colors hot at :9090. No HA/scale claims.
- Reconciler and events spine **off** (base compose defaults), matching the smoke default
  topology; the load measures the synchronous money path + webhook intake.
- Webhook abuse-control limits raised for this run only (see tuning row) — they gate smoke
  and demo, not the baseline.
- `chaos.*` all OFF in the simulator (design.md §12 defaults).

## Reproduction

```bash
# export DARGENT_WEBHOOK_RATE_LIMIT_CAPACITY=10000 DARGENT_WEBHOOK_RATE_LIMIT_REFILL_PER_SECOND=100
docker compose -f docker/compose.yaml up -d --force-recreate api-blue api-green
# insert psp_test_ API key (scripts/ci-runtime-smoke.sh P2 pattern)
docker run --rm --network host \
  -e DARGENT_K6_API_BASE=http://localhost:8080 -e DARGENT_K6_PSP_BASE=http://localhost:8090 \
  -e DARGENT_K6_API_KEY="$KEY" -v "$PWD/scripts/load:/scripts" \
  grafana/k6:latest run /scripts/k6-money-path.js
```