# Load Test Baseline

Performance budgets-as-code, run consultatively until calibrated. Numbers here are
**infrastructure-overhead tripwires, not capacity results**: the goal is to notice architectural regressions
(n+1 queries, missing indexes, synchronous surprises), not to certify throughput of a single bare-metal host.

**Status:** `consultative` — the CI performance job runs with `continue-on-error`. Promoting budgets to a hard
gate is a deliberate decision after 2–3 calibrated runs (see §5).

---

## 0. Back-of-envelope sizing — assumptions, not measurements

Engineering assumptions used to derive the E6 messaging defaults (spec §4.1) — **not measured capacity**.
Every consequence here is arithmetic; nothing here has been load-tested yet. Re-anchor with real measurements
before promoting any budget to a hard gate.

| Premise (assumed, stated) | Value | Consequence |
|---|---|---|
| Steady payments/day | 50 000 | ×2 events ≈ 100 000 events/day ≈ **1.16 evt/s avg** |
| Peak multiplier | 20× | ≈ **23 evt/s peak sustained** |
| Envelope size | ~1 KB (jsonb + indexes ≈ 2–3×) | ~100 MB/day table growth ungoverned ≈ **3 GB/month** → purge is not optional |
| Relay ceiling | workers 2 × batch 32 / poll 1 s | **64 evt/s** ≫ peak with 2.7× headroom (hence `POLL_MS=1000`, not 5000) |
| Outage drain (RPO ≤ 15 min) | 1 h outage ≈ 4 200 events / 64 evt/s | **~66 s to drain** ✓ |
| Steady table size | 7-day retention | ~700 MB rows + ~1.4 GB indexes — bounded ✓ |
| SQS FIFO throughput | 300 TPS base | 23 evt/s peak: no high-throughput mode needed |

Knob defaults cited from this table: `DARGENT_RELAY_WORKERS=2`, `DARGENT_RELAY_BATCH=32`,
`DARGENT_RELAY_POLL_MS=1000`, `DARGENT_OUTBOX_RETENTION_DAYS=7`. Changing a default without re-deriving
this table is a spec violation.

### Ledger growth addendum (E7 — assumptions, not measurements)

The confirmed subset at MVP volume lands in the ledger's **append-only journal and postings, which are never
purged** (the deliberate contrast with the outbox's 7-day retention, design.md §5.2). Same assumption set as
the outbox table above (50 000 payments/day → ~100 000 events/day).

| Premise (assumed, stated) | Value | Consequence |
|---|---|---|
| Confirmed events/day | ~100 000 (incl. non-posting created/failed) | ~100 000 `journal_entries` + ~300 000 `postings` (3 per confirmed) per day |
| Journal+postings growth | ~40 MB/day incl. indexes | **permanent, no purge** → ~1.2 GB/month, ~14 GB/year — acceptable bare-metal; archival is E14's row |
| Proof query cost | `O(postings)` across `ledger.postings` + `ledger.balances` | fine at this size (single-digit ms); revisit with measured floors before any hard gate (§5) |

Consequence: the journal is treated as a financial record (bounded by MVP volume today); E14 is explicitly
scoped to archival, not rollback. Growth knob is NOT a retention knob — lowering it is not a remedy.

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
