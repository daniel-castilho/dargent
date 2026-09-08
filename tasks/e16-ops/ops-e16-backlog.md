# E16 — Backlog (S0–S6, compact)

Q-batch first; 1 PR per step; evidence verbatim; STOP after Block 1. M5 fence hard (no card/Redis/
k6-gate/reprocess work).

---

## BLOCK 1

### S0 — v1.1.0 (position per owner Q3 — arrives answered with this package)

- **If TODAY**: docs commit (CHANGELOG `[1.1.0]` section + README L196 "cuts v1.0.0"→past tense with
  tag) → `git tag -a v1.1.0 -m "Release 1.1.0: operational hardening"` on main → release run green →
  verify `:1.1.0` image + jar + SBOM + digest in body (rite = E14's, unchanged).
- **If END**: skip; executed as S6's tail (after flip+citation, tag on the final head — single
  release carries all of E16).
- Evidence: release run id, digest pair, asset list.

### S1 — Alertmanager in the metrics profile (1 PR)

1. `docker/alertmanager/alertmanager.yml`: route tree (severity → receivers), receiver =
   **webhook-logger stub** (local HTTP sink that logs; NO pager, NO external sink — Q-batch confirms
   stub shape).
2. compose: `alertmanager` service in the `metrics` profile; Prometheus `alerting:` section points
   at it (with the resolver/dns nuances of compose).
3. CI: `amtool check-config` step beside promtool (addition only).
4. Doc: observability.md gains the Alertmanager section (what routes exist, how an operator points
   a real receiver later).
5. Evidence: amtool CI run id; compose config check; one manual `amtool --alertmanager.url` query
   showing alerts flowing (or the stub log line) in a dispatch/local log.

### S2 — PITR v2: off-disk WAL (1 PR)

1. Harness v2: WAL archive container/volume SEPARATE from the Postgres volume (the E15 caveat
   verbatim answered); `archive_command` → its volume; base backup + kill + replay to target.
2. Measured achieved-RPO window v2; comparison row vs the ≈6s same-disk result (honest delta —
   expect slightly worse; the number that matters is the one that survives a dead disk).
3. Q-batch: CI-ify as dispatch job AND/OR wire into release.yml (budget evidence decides; either
   disposition is honest, the record says which).
4. `docs/drills/pitr-v2-<date>.md`: procedure, transcript, RPO v2, limits.
5. Evidence: drill doc + transcript (text only).

### S3 — k6 honest run (1 PR)

1. Same `scripts/load/k6-money-path.js`; environment = demo overlay ON (spine real) + DEFAULT
   limiter limits (100/0.5 — abuse controls live during load).
2. Publish BESIDE the 414 rps run: two-row comparison table in `docs/load-test-baseline.md`
   (happy-path vs honest-path; p95 create/confirm, rps, errors, hardware, commit). Expect worse
   numbers — the delta IS the result (per-instance limiter will show its teeth at burst).
3. Note in doc: "this honest number seeds the M5 k6-gate threshold decision (D4)" — recorded, not
   gated.
4. Evidence: k6 summary verbatim; the comparison table.

## STOP — BLOCK 1 AUDIT (channel). Her self-audit record attaches to the handoff; channel audits.

---

## BLOCK 2

### S4 — Limiter posture (1 PR, may be docs-only)

- Path A: **documented posture** — observability/runbook state per-instance buckets, quota math per
  replica (canary = 2×), when shared-store becomes warranted. Closes the catch honestly.
- Path B: **shared-store limiter now** — heavier; if she proposes it, the Q-batch must show why M5's
  Redis can't simply host it (D3 tie-in). Default leaning: A with explicit M5-Redis deferral.
- Evidence: the row/paragraph diff + (if B) ITs.

### S5 — dependabot.yml (1 PR)

- Weekly `schedule.interval`; groups minor+patch; `production-deps` focus; ignore-list for the
  noise-prone test deps. OWASP/Trivy bites stay enforcement (dependabot = early warning PRs).
- Evidence: config diff; first grouped-PR behavior described (or noted as pending next Monday tick).

### S6 — Truth sweep + flip + citation (+ v1.1.0 tail if position=end)

1. README/observability/runbook: Alertmanager real, PITR v2 real, honest k6 number, limiter posture
   — no future tense anywhere (README L196 class checked across docs).
2. CHANGELOG `[Unreleased]` consolidated.
3. Flip E16 ☐→✅ (run pairs) = LAST content commit → citation (ONE commit) → silence.
4. Evidence: verbatim chain + run list.
