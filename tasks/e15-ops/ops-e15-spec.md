# E15 — Spec: Operational Hardening (alerts, load baseline, scale restore, PITR, webhook abuse, DEBT dispositions)

Epic row (minted at S0): `| E15 | Operational hardening: alerts, load baseline, scale restore, PITR,
webhook abuse controls, DEBT dispositions | api, deploy, docs | E11, E13, E14 | post-1.0.0 | ☐ |`
Binding context: v1.0.0 shipped (tag @ `601a669`); external baselines 5.3→6.3→6.9 with the frame
"versão de engenharia vs versão de operação"; DEBT-7/8 window opened by owner adjudications.
Companions: `ops-e15-backlog.md` (step detail), `ops-e15-sequence.md` (blocks/PRs/aborts).

---

## §1 Objective

Upgrade v1.0.0 from engineering version to operations version with EVIDENCE: abuse controls in the
app (not on a wish), alert rules that are tested firing, a published load number, a restore drill at
production-like scale, a measured PITR rehearsal — and both registered debts disposed honestly.

## §2 Non-goals (scope fence)

- **No Alertmanager / pager** (stance: trigger-activated; E16 candidate).
- **No new tag/release** — v1.1.0 is an owner call post-epic; CHANGELOG accumulates `[Unreleased]`.
- **No k6 in per-push CI** (consultative baseline only; M5 stance unchanged).
- **No gate relaxation of any kind** (floors owner-fixed; promtool is an ADDITION).
- Tracing stays out (trigger-activated). Card rails/PIX real rail = out (M5-era).
- `dependabot.yml` stays out (bumps flow via gated bites; revisit post-E15).
- No refactors beyond DEBT-7's adjudicated path.

## §3 Contracts inherited & honored

| Contract | Source | Disposition in E15 |
|---|---|---|
| Floors owner-fixed 70/75/80/50/40 | E13 adjudication | untouched; DEBT-7 proof = floors IDENTICAL |
| DEBT-7 window post-v1.0.0 | owner 2026-09-06 | S6 disposition (Path A or B, no silence) |
| DEBT-8 accepted-for-v1 + NGINX edge | owner 2026-09-07 | S1 SUPERSEDES: in-app closure, row RESOLVED |
| Demo overlay rider | owner 3/3 intake 2026-09-07 | S0 lands it (TD-35) |
| Q-batch before execution; conflicts asked | DOD §2 / post-TD-30 | standing |
| Flip → citation ONE → nothing after | E11–E14 precedent | S7 |
| Closure audit checks EVERY deliverable vs tree | TD-34/35 lessons | institutional (channel-side) |

## §4 New configuration surface (each: rationale + safe default + tests tune explicitly)

| Var (proposed) | Purpose | Default | Notes |
|---|---|---|---|
| `DARGENT_WEBHOOK_RATE_LIMIT_*` | in-app 429 control | generous (never touches smoke/demo) | algorithm via Q-batch |
| `DARGENT_WEBHOOK_BODY_CAP_BYTES` | in-app 413 control | generous | Q-batch value |
| Grafana provisioning env (rider) | dashboards-as-code | metrics-profile only | no Alertmanager |

No other runtime surface changes. CI gains: promtool step; `restore-drill-scale` (dispatch-only).
Anything beyond this table → STOP + Q-batch.

## §5 Acceptance matrix (fill at S7; each row needs run id / file path / commit)

| Step | Deliverable | Evidence | Status |
|---|---|---|---|
| S0 | demo overlay + README cover + E15 row + id-count | | ☐ |
| S1 | 429/413 ITs + DEBT-8 RESOLVED end-to-end | | ☐ |
| S2 | rules fire/quiet proven in CI + bite-proof (+ rider) | | ☐ |
| S3 | load baseline published w/ hardware | | ☐ |
| S4 | restore-at-scale RTO recorded | | ☐ |
| S5 | PITR rehearsal w/ measured RPO window | | ☐ |
| S6 | DEBT-7 Path A (floors identical) or Path B (rationale) | | ☐ |
| S7 | truth sweep + flip + citation | | ☐ |

## §6 Evidence policy

- Run ids + shortshas verbatim; drill records/load baselines as TEXT artifacts under `docs/`.
- Negative paths proven at least once: 429, 413, rule-fires, rule-quiet, broken-rule-red (bite).
- Measured numbers are recorded, never extrapolated; reductions in drill scope are recorded as such.
- **Closure checklist (TD-35 institutionalized): the channel verifies EVERY adjudicated deliverable
  against the tree — including yaml-only riders — before declaring.**
- If evidence and prose disagree, evidence wins; prose fixed in the same PR.

## §7 Post-E15 (channel-side)

1. Channel audits Block 1 (STOP), then Block 2; declares E15 CLOSED.
2. Maturity protocol: external baseline #4 re-run (same framing) + OUR rubric scoring when owner
   says go — E15 artifacts are the new evidence rows (alerts/restore-scale/PITR move the
   "operations version" question).
3. E16 candidates: Alertmanager/pager (trigger review), dependabot.yml, tracing (trigger), v1.1.0
   cut (owner call), DEBT-8 NGINX edge (only when public deployment exists).
