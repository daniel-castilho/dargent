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

## §5 Acceptance matrix (fill at S6)

| Step | Deliverable | Evidence | Status |
|---|---|---|---|
| S0/S6 | v1.1.0 shipped (position per owner) | | ☐ |
| S1 | Alertmanager + amtool in CI | | ☐ |
| S2 | PITR v2 off-disk, RPO v2 measured | | ☐ |
| S3 | honest k6 published beside 414 | | ☐ |
| S4 | limiter posture closed | | ☐ |
| S5 | dependabot.yml live | | ☐ |
| S6 | sweep + flip + citation | | ☐ |

## §6 Post-E16

1. Channel declares CLOSED; **M5 package (5/5) goes to owner for commissioning** — scoping doc:
   `internal-notes/m5-scoping.md` (recon done; D1–D4 decision areas).
2. Maturity protocol per owner's standing answer.
3. External baseline #5 opportunity: same framing, post-v1.1.0+E16.
