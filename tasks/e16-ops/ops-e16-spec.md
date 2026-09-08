# E16 — Spec: Operational Hygiene

Epic row (mint at S1 PR): `| E16 | Operational hygiene: Alertmanager, PITR v2, honest k6, limiter
posture, dependabot | deploy, docs, api | E12, E15 | post-1.0.0 | ☐ |`

## §1 Objective

Close the fine-operation queue with evidence and zero scope creep toward M5; ship v1.1.0 so the
named artifact carries E15 (+E16).

## §2 Non-goals (M5 fence — hard)

Card strategy, Redis/cache, k6-as-gate, webhook reprocessing: **OUT** (design.md:632/640 — locked
for M5, package in prep channel-side). Also out: pager/on-call wiring (Alertmanager ships with a
logging stub only), any gate change, any floor change.

## §3 Contracts

| Contract | Source | Disposition |
|---|---|---|
| M5 fence | design.md:640 lock | §2; violations = P3 process defect |
| Additions-only to gates | E13–E15 | amtool joins promtool |
| Measured numbers, disclosures verbatim | E15 | k6/PITR v2 |
| Block audits channel-side until TD-36 ruled | E15 audit record finding | her self-audit attaches; does not replace |
| Flip→citation ONE→nothing after | E11–E15 | S6 |

## §4 New configuration surface

| Item | Purpose | Notes |
|---|---|---|
| `alertmanager` service (metrics profile) | alerts routable | logging-stub receiver; no pager |
| PITR v2 archive volume/container | off-disk WAL | the E15 caveat answered |
| dependabot.yml | early-warning PRs | weekly, grouped; bites stay enforcement |

No new app env vars expected; if the limiter posture (S4) lands Path B, its vars come via Q-batch.

## §5 Acceptance matrix (filled at S6; each row: run id / file path / commit)

| Step | Deliverable | Evidence | Status |
|---|---|---|---|
| S0 | v1.1.0 shipped (TODAY) | tag `v1.1.0` @ `5cd89f9`; release run `34268739782` GREEN; digest triplet `sha256:e0221249…` (image == body == SBOM purl); jar `0edfe8c0…`; `docs/releases/v1.1.0.md` artifact map | ✅ |
| S1 | Alertmanager + amtool in CI | PR #26 `47b05cf`; amtool step in build job; wiring evidence (alert routed + logged by stub) verbatim in observability.md §5 | ✅ |
| S2 | PITR v2 off-disk, RPO v2 measured | PR #27 `247a3b7` + CI record PR #30: dispatch `34276115886` job `pitr-drill` SUCCESS (RPO ~5s; local 6–8s); `docs/drills/pitr-v2-2026-09-08.md` | ✅ |
| S3 | honest k6 published beside 414 | PR #29 `3b3f548`: two-row table in `docs/load-test-baseline.md`; raw `scripts/k6-honest-2026-09-08.raw.out` | ✅ |
| S4 | limiter posture closed (Path A) | PR #32 `7fca3dd`: observability.md §3 posture block + runbook §7 row; M5 deferral explicit (`tasks/m5-scoping.md` D3) | ✅ |
| S5 | dependabot.yml live | PR #33 `1d9b0e7`: `.github/dependabot.yml`; first grouped PRs opened same-day (evidence: PRs #34–#38); infra majors fenced (ignore-list; #35/#36 closed deferred) | ✅ |
| S6 | sweep + flip + citation | this flip (LAST content commit); citation = ONE commit after | ✅ |

## §6 Post-E16

1. Channel declares CLOSED; **M5 package (5/5) goes to owner for commissioning** — scoping doc:
   `tasks/m5-scoping.md` (recon done; D1–D4 decision areas).
2. Maturity protocol per owner's standing answer.
3. External baseline #5 opportunity: same framing, post-v1.1.0+E16.
