# Service Level Objectives

Engineering targets measured by our own metrics — not marketing uptime. Dargent runs as a single-host
on-premises deployment; SLOs exist to make regressions visible and to discipline priorities, and the error
budget converts "slow down and fix reliability" from opinion into policy.

---

## 1. Definitions

| # | SLI | SLO | Window | Source of truth |
|---|---|---|---|---|
| S1 | API success ratio (non-5xx responses, business endpoints) | **≥ 99%** | 30 days rolling | NGINX logs / Micrometer http metrics | (M4) |
| S2 | Latency — `GET /v1/payments/{txid}` | **p95 < 100 ms** | 7 days | Prometheus histograms | (M4) |
| S3 | Latency — `POST /v1/payments` | **p95 < 250 ms** | 7 days | Prometheus histograms (chaos off) | (M4) |
| S4 | Latency — webhook intake | **p95 < 150 ms** | 7 days | Prometheus histograms | (M4) |
| S5 | Confirmation propagation — a payment the PSP knows is confirmed is visible as `CONFIRMED` (webhook or reconciler) | **≥ 99.9% within 10 min** | 30 days | `dargent_reconciler_confirmations_total` + payment state audit query | *(live, E11)* |
| S6 | Event delivery — outbox lag | **p95 < 30 s**; no event `PENDING` > 5 min | 7 days | `dargent_outbox_lag_seconds` | *(live, E11)* |
| S7 | Ledger integrity — daily balance proof passes (`Σ DR = Σ CR`, projection == lines) | **100%** | daily job | proof job exit status (recorded in drill log) | (M4) |
| S8 | Money durability — a `CONFIRMED` payment, once journaled, is never lost | **100% (invariant, no budget)** | forever | restore drills + proof jobs | (M4) |

S8 is an **invariant, not an SLO**: no error budget exists for losing money. Any S8 breach is a
stop-the-line incident with a written post-mortem and a correcting-entry analysis.

## 2. Measurement honesty

- Single host, self-measured, hobby-grade on-premises. We do not claim five nines; we claim **measured,
  documented behavior with declared failure modes**.
- Deploy windows are part of the measurement (blue-green should make them invisible at p95; if they aren't,
  that's an S2–S4 breach like any other).
- Latency SLOs assume chaos knobs off; chaos-mode performance is explicitly out of scope.

## 3. Error budget policy

- Budget per SLO = `1 − target` over the window (e.g., S1: 1% of requests over 30 days).
- **Budget remaining > 50%:** feature work proceeds normally.
- **Budget 20–50%:** reliability items rise to the top of the milestone; no new surface area without tests.
- **Budget < 20%:** feature freeze for the affected surface; only reliability/bugfix work until recovery.
- **Invariant breach (S7/S8):** freeze everything, post-mortem within the week, lessons.md entry mandatory.

## 4. Review cadence

- SLO status reviewed at every milestone close (numbers + budget spend recorded in the release notes).
- Targets may change only through a decision record (data-model-decisions.md format) — moving the goalposts
  silently is how SLOs stop meaning anything.
