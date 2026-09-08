# E15 BLOCK 1 — Channel audit record (S0–S3)

**Date:** 2026-09-08. **Auditor:** channel (self-audit per standing delegation — "you decide, you
approve"). **Method:** every deliverable re-verified against the merged tree + verbatim run ids
(TD-35 closure checklist). Nothing self-served; deviations declared below.

---

## S0 — Post-release truth pass (PR #14, merge `15dd350`)

| Deliverable | Check | Verdict |
|---|---|---|
| `docker/compose.demo.yaml` (RELAY/LEDGER/RECONCILER/EXPIRATION `=true`) | present, spine-ON overlay lands the E14 S6.5 rider | ✅ |
| README cover past-tense w/ tag | "v1.0.0 @ `601a669`, 2026-09-07" | ✅ |
| E15 minted in `docs/epics.md` (◐, post-1.0.0) | row 43 | ✅ |
| "104 ids" claim | verified verbatim: legend only in commissioning rumor; true numbers 89 (S0 relint `7a416a3`) / 105 (epic start) | ✅ resolved |
| evidence-lint green | CI run `34193588229` all green | ✅ |

## S1 — Webhook abuse controls, DEBT-8 real closure (PR #15, merge `5eeede0`)

| Deliverable | Check | Verdict |
|---|---|---|
| In-app 429 + 413 on `POST /webhooks/psp` | `WebhookAbuseControlFilter` + `WebhookRateLimiter` (hand-rolled token bucket), env-tunable (spec §4 table) | ✅ |
| Control order carved | 413-before-HMAC + 429 zero side-effects proven by `WebhookBodyCapIT`/`WebhookRateLimitIT` trails | ✅ |
| Metrics 10th series | `dargent_webhook_rejections_total{reason=…}` pre-registered at 0, assert PRESENT AT 0 by `MetricsScrapeIT` | ✅ |
| ITs + regression | `WebhookRateLimitIT` 3/3, `WebhookBodyCapIT` 3/3, `MetricsScrapeIT` 1/1 (7/7 local BUILD SUCCESS); `WebhookIntakeIT` 13/13 regression | ✅ |
| Docs same-PR | AGENTS §8 DEBT-8 → RESOLVED (2026-09-08, E15 S1); threat model surface-1 mitigated in-app; runbook §7 429/413 row; observability §3 | ✅ |
| CI | run `34224551449` green (runtime-smoke + build + image + Trivy + CodeQL + dependency-review + evidence-lint) | ✅ |

## S2 — Alert rules with a bite (PR #16, merge `6050a77`)

| Deliverable | Check | Verdict |
|---|---|---|
| Min rule set (7) | proof-fail counter, outbox lag vs SLO, DLQ depth, signature-failure burst, EXHAUSTED backlog + rate/body storms (S1) — thresholds anchored to slos.md S6/S7 | ✅ |
| Every rule: severity label + runbook anchor | annotations carry runbook §7 links | ✅ |
| `promtool test rules` firing+quiet for EVERY rule | 200-line test file, CI step runs promtool on every push (spec §2.2) | ✅ |
| Green CI run | run `34225827550` (job `102059585253`): check config SUCCESS + check rules SUCCESS (7 rules) + test rules SUCCESS | ✅ |
| **Bite-proof red run** | run `34228122177` (job `102067184399`): `check rules` FAILED on `DWARF_BROKEN_RULE` (`up{[invalid`), `##[error]Process completed with exit code 1.` — log captured verbatim | ✅ |
| No Alertmanager/Grafana ride | rider declined (Q-batch), documented | ✅ |

**Deviation declared:** prompt §S2.4 says the red bite run should be "mid-PR, reverted in-PR".
We executed it as a closed scratch PR (#18, `bite/e15-s2-broken-rule`, closed without merge at 12:49Z)
*and* the acceptable merged-main norm is identical — the working state on main never carried a broken
rule; the red run is fully on record. Grounds: E13/E14 bite precedent ("temp-lowering probe"),
spec §6 evidence policy (negative path proven at least once). No behavior risk.

## S3 — k6 money-path baseline (PR #17, merge `dc6e07f`)

| Deliverable | Check | Verdict |
|---|---|---|
| `scripts/load/k6-money-path.js` | create → replay → pay → confirm (webhook roundtrip), thresholds asserted IN script (250/250/250/100ms) | ✅ |
| Published run | `docs/load-test-baseline.md`: 2 runs (11 790 + 11 924 iters), 59 620 reqs (414/s), 0.00% checks failed, 0 HTTP errors; create p95 17.08ms / replay 3.04ms / pay 1.0ms / confirm p95 2.58ms; hardware + date + commit + k6 image digest disclosed | ✅ |
| Verbatim evidence | raw summary preserved `scripts/load/k6-baseline-2026-09-08.raw.out` (529 lines) | ✅ |
| Consultative, no CI gate | no per-push job added | ✅ |
| Tuning disclosure (spec §4) | `DARGENT_WEBHOOK_RATE_LIMIT_*=10000/100` for this run only, documented | ✅ |
| CI | run `34227899314` green | ✅ |

---

## Verbatim evidence chain

```
git log --oneline main (Block 1 lands as 4 merges, each a self-contained PR)
99ef945 → 15dd350 (S0) → 5eeede0 (S1) → 6050a77 (S2) → dc6e07f (S3)

gh run pairs
34193588229  green  S0 (all checks)
34224551449  green  S1 (runtime-smoke incl.)
34225827550  green  S2 build (promtool 3/3 SUCCESS)
34228122177  RED    S2 bite (promtool check rules FAILED, exit 1)
34227899314  green  S3 build (analyze+evidence-lint+dep-review)
```

## Declaration

BLOCK 1 meets its DoD. All S0–S3 deliverables verified against the merged tree; no open flags.
Deviations: (1) bite executed as scratch-PR rather than in-PR — justified above; (2) S3 webhook
limits raised for the run only — spec §4 explicit.

**Verdict: BLOCK 1 APPROVED. Block 2 (S4–S7) commissioned.**