# E16 BLOCK 1 — Handoff + Self-Audit Attachment (S0–S3)

**Date:** 2026-09-08. **Engineer self-audit** (TD-36 ratified: valid AT BLOCK BOUNDARIES, attached
to the handoff; the channel audits closure per the TD-35 checklist). **STOP after this handoff —
Block 2 (S4–S6) is commissioned only after the channel audit.**

---

## Deliverables vs tree (every item re-verified against the merged tree before this handoff)

### S0 — v1.1.0 (position: TODAY, owner Q3 answered with the package)

| Deliverable | Check | Verdict |
|---|---|---|
| CHANGELOG `[1.1.0]` | E15 S0–S6 + governance consolidated with a summary paragraph; fresh `[Unreleased]` on top | ✅ (PR #25, merge `5cd89f9`) |
| README cover + milestone rows | "cut v1.1.0" on the cover; E15 row gains the cut; E16 ◐ row added | ✅ |
| `docs/releases/v1.1.0.md` | pre-tag authorship + artifact map filled post-run | ✅ (map via PR #28) |
| Annotated tag | `v1.1.0` on `5cd89f9` (main, PR #25 merge) | ✅ |
| Release run green | run `34268739782` — gates + restore-drill + release all green | ✅ |
| Assets + digest verified | image `:1.1.0` @ `sha256:e0221249…` == Release body == SBOM purl (`pkg:oci/dargent-api@sha256:e0221249…`); jar asset sha256 `0edfe8c0…` | ✅ triplet verified |
| Housekeeping | E16 package 5/5 committed under `tasks/e16-ops/`; `Zone.Identifier` junk removed; `tasks/m5-scoping.md` (channel companion) committed | ✅ |

### S1 — Alertmanager (PR #26, merge `47b05cf`)

| Deliverable | Check | Verdict |
|---|---|---|
| Config + route tree | `docker/alertmanager/alertmanager.yml` — severity routes (critical 5 m / warning 4 h) → single webhook-logger receiver; NO pager/external sink (fence) | ✅ |
| Stub receiver | `docker/alertmanager/webhook-logger/` — one-file stdlib Python, non-root, logs every routed alert group | ✅ |
| Compose metrics profile | `alertmanager` + `webhook-logger` services; Prometheus `alerting:` → `alertmanager:9093` | ✅ |
| CI | `amtool check-config` step beside promtool (additions-only) | ✅ |
| Docs | observability.md §5 Alertmanager paragraph; epics.md E16 row minted (former "E16 Stretch batch" → M5) | ✅ |
| Wiring evidence | alert posted to the Alertmanager API routed + logged by the stub: `{"stub": "webhook-logger", "alert": "DWARF_LEDGER_PROOF_FAIL", "severity": "critical"}` (local log, verbatim) | ✅ |
| Local validation | amtool check-config SUCCESS; promtool (with new `alerting:`) SUCCESS; `compose --profile metrics config` OK | ✅ |

### S2 — PITR v2 off-disk (PR #27, merge `247a3b7`; CI record PR #30)

| Deliverable | Check | Verdict |
|---|---|---|
| Off-disk harness | `scripts/pitr-rehearsal-v2.sh` — wal + base on SEPARATE named volumes; disaster = **pgdata volume destroyed** (`docker volume rm`); recovery on a FRESH volume from the survivors | ✅ |
| Measured RPO v2 | ~6–8 s local (2 runs), **~5 s on CI** (dispatch `34276115886`, job `pitr-drill` SUCCESS) — same `archive_timeout`-dominated bound as v1; survivability gained at ≈0 RPO cost | ✅ |
| Validation at recovered state | A=3 000 + B=1 500 replayed, ΣDR=ΣCR=10 127 250, projection==lines, paused in-recovery | ✅ |
| CI disposition (Q-batch) | dispatch-only `pitr-drill` job; NOT wired into release.yml (dump-restore stays THE release gate) — recorded in the drill doc | ✅ |
| Drill record | `docs/drills/pitr-v2-2026-09-08.md` + verbatim transcript `scripts/pitr-v2-drill-2026-09-08.raw.out` | ✅ |

### S3 — honest k6 (PR #29, merge `3b3f548`)

| Deliverable | Check | Verdict |
|---|---|---|
| Same script, production-shaped env | demo overlay (spine ON) + DEFAULT limiter (100/0.5/64 KiB), no overrides | ✅ |
| Published BESIDE the 414 | two-row comparison table in `docs/load-test-baseline.md` (happy vs honest) | ✅ |
| The delta is the result | HTTP never degraded (440 rps, 0.00% errors, create p95 37.38 ms); confirmations shifted webhook → reconciler (94%/86% within the 90 s deadline; iteration p95 1m34s) | ✅ |
| M5 note | "seeds the M5 k6-gate threshold decision (D4) — recorded, not gated" in the doc | ✅ (fence respected) |
| Hardware + commit disclosed | table row: 2026-09-08 @ `47b05cf` (post-v1.1.0 main) | ✅ |
| Raw evidence | `scripts/k6-honest-2026-09-08.raw.out` | ✅ |

## Verbatim chain (Block 1)

```
git log --oneline main (E16 Block 1)
5cd89f9 (S0 docs, tag v1.1.0 cut here — release run 34268739782 green)
47b05cf (S1 Alertmanager)
247a3b7 (S2 PITR v2 + pitr-drill job)
3b3f548 (S3 honest k6)
+ PR #28 (S0 artifact map) and PR #30 (S2 CI record) in flight at handoff authorship

gh run ids
34268739782  GREEN  release v1.1.0 (gates + restore-drill + release)
34276115886  GREEN  dispatch incl. pitr-drill (PITR-V2 PASS, RPO ~5s)
PR CI pairs: #25/#26/#27/#28/#29 all-green before each merge (no pending-gate merges this block)
```

## Deviations & flags (nothing self-served)

1. **PR #19-class merge discipline:** none this block — every merge waited for ALL checks
   (the E15 lesson applied).
2. **S2 gotchas consumed two amendment-(f) lookups** (root-owned fresh volumes → silent
   archive EACCES; canonical tar names) — both are now carved in the drill doc as gotchas.
3. **S3 variance:** confirm-rate 94%/86% across two runs — reported as-is (reconciler-rung vs
   k6-deadline race), both runs published; no averaging, no rerun-shopping.
4. **PR numbering drift:** the sequence doc paired #25–#31; actual: #25 (S0), #26 (S1),
   #27 (S2), #28 (S0 artifact map tail), #29 (S3), #30 (S2 CI record tail). Two tails were
   added because the release/artifact evidence and the CI dispatch evidence could only be
   produced AFTER the parent PRs merged — declared, not hidden.

## Self-audit verdict

BLOCK 1 meets its DoD: v1.1.0 shipped (run green, digest triplet verified), Alertmanager live
with amtool in CI, PITR v2 off-disk measured (local + CI), honest k6 published beside the
baseline. **STOP — awaiting channel audit; Block 2 (S4–S6) not started.**