# Load Test Baseline

Performance budgets-as-code, run consultatively until calibrated. Numbers here are
**infrastructure-overhead tripwires, not capacity results**: the goal is to notice architectural regressions
(n+1 queries, missing indexes, synchronous surprises), not to certify throughput of a single bare-metal host.

**Status:** `consultative` — the CI performance job runs with `continue-on-error`. Promoting budgets to a hard
gate is a deliberate decision after 2–3 calibrated runs (see §5).

---

## 1. Scenarios & budgets

| Scenario | Script | Request | Budget (p95) | Notes |
|---|---|---|---|---|
| `create-payment` | `perf/scenarios/create-payment.js` | `POST /v1/payments` | **< 250 ms** | Includes PSP call to simulator (chaos off) + idempotency + outbox write |
| `get-payment` | `perf/scenarios/get-payment.js` | `GET /v1/payments/{txid}` | **< 100 ms** | Direct table read; the hot merchant path |
| `list-payments` | `perf/scenarios/list-payments.js` | `GET /v1/payments?limit=20` | **< 150 ms** | Cursor pagination over seeded dataset |
| `webhook-intake` | `perf/scenarios/webhook-intake.js` | `POST /webhooks/psp` | **< 150 ms** | HMAC validation + conditional UPDATE + outbox |

All budgets measured at the NGINX entrypoint with the canary disabled (single fleet active).

## 2. Methodology

- Runner: `grafana/k6` container, **version-pinned** in `scripts/performance-baseline.sh`.
- Dataset: seeded by `perf/seed.js` — 2 merchants, 5,000 payments across states, 500 refunds.
- Per scenario: 30 s warm-up, 2 min measurement window, ramp to 50 VUs, think-time 0 (closed model).
- Success criteria per run: p95 within budget **and** error rate < 0.1% **and** no DLQ growth and
  outbox lag returning to < 5 s after the run.
- Chaos knobs off; `CHAOS_PSP_LATENCY_MS=0`. The simulator runs on the same host — its latency is part of
  what `create-payment` measures (documented, not subtracted).

## 3. Running

```bash
# full baseline (boots compose stack if not up, warms up, runs all scenarios, prints summary)
scripts/performance-baseline.sh

# single scenario against an already-running stack
k6 run perf/scenarios/get-payment.js -e BASE_URL=http://localhost:8080
```

Results land in `perf/results/<date>-<scenario>.json` (summary + thresholds) and are uploaded as CI artifacts
by the `performance` job.

## 4. Baseline results

| Date | Commit | Scenario | p50 | p95 | Budget | Verdict |
|---|---|---|---|---|---|---|
| — | — | — | — | — | — | *pending first calibrated run (M4)* |

## 5. Promotion to hard gate

Requirements, all three:

1. 2–3 consecutive runs with stable p95 (no first-run JIT/scale noise) recorded in §4.
2. Budgets re-anchored to measured floors (initial numbers are engineering guesses, stated as such).
3. A documented escape hatch: the gate can be waived by a declared, dated decision in the PR when infra
   (not code) causes the regression.
