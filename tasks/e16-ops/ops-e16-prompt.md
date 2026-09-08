# E16 — Operational Hygiene: Alertmanager, PITR v2, honest k6, limiter posture, dependabot — COMMISSIONING PROMPT

> Channel: owner authorization 2026-09-08 ("E16 autorizado" + M5 prep in parallel + v1.1.0).
> Status: COMMISSIONED. Engineer confirms receipt + Q-batch BEFORE executing (DOD §2); conflicts
> are ASKED, never chosen. Companions: `ops-e16-{backlog,sequence,spec,execution-prompt-block1}.md`.
> Precedents: E11–E15. This epic is COMPACT by design (~1 day class) — scope fence is hard.

---

## 0. Context

- **E15 CLOSED** (7.1 external composite); v1.0.0 shipped; M0–M4 ✅. E16 = the fine-hygiene queue
  the E15 day taught the repo to execute. **M5 scoping runs in parallel (channel-side)** — any M5
  territory found here is fenced OFF (design.md:640 lock moves to M5, not to E16).
- E16 items are the 4th analysis's catches + standing queue: Alertmanager, PITR hardening, honest
  k6, limiter posture, dependabot. Plus **v1.1.0** (authorized; position pending owner Q3).

## 1. Hard contracts

1. **M5 fence (new, hard)**: card/Redis/k6-as-gate/webhook-reprocess = OUT (design.md lock). The
   E16 k6 run feeds the FUTURE gate's threshold (M5 D4) — it does not create any gate.
2. **Gates untouched**: floors owner-fixed; promtool steps unchanged; E16 adds `amtool check-config`
   the same way promtool entered (additions only).
3. **Every new moving part ships with a check**: Alertmanager config validated by `amtool` in CI;
   PITR v2 validated by measured replay; k6 by disclosure; dependabot by config-review.
4. **Numbers measured, never extrapolated**; disclosures verbatim in docs (E15 precedent).
5. **No new runtime surface beyond the spec §4 table**; beyond it → STOP + Q-batch.
6. **Chain discipline**: Q-batch first; 1 PR per step/pair; flip = last content commit; citation =
   exactly ONE after; nothing after. Closure audit checks EVERY adjudicated deliverable vs tree
   (TD-35 institutional) — including compose profiles and config files.
7. **Process ruling (UPDATED by owner ratification 2026-09-08 — TD-36):** the "you decide, you
   approve" delegation WAS issued and is now on register record. Her self-audit is valid AT BLOCK
   BOUNDARIES (attached to handoffs); the channel still performs the CLOSURE audit (TD-35 checklist
   permanent — every adjudicated deliverable vs tree).

## 2. Steps (detail in backlog)

- **S0 — v1.1.0** (position per owner Q3: today-as-first-step OR after-S6-flip as the single
  release): docs commit (CHANGELOG `[1.1.0]` + README L196 "cuts"→past) → annotated tag → release
  run (gates + restore-drill + release) green → verify assets + digest; SBOM + jar attached.
  If position = end: this becomes S6's tail and the flip precedes it.
- **S1 — Alertmanager** (metrics profile): container + config (route tree, receiver = webhook-logger
  stub — NO pager); `amtool check-config` in CI; rules' `for`/severity unchanged; compose profile
  wiring + doc line. Q-batch: receiver stub shape.
- **S2 — PITR v2**: WAL archive on a SEPARATE container/volume (off the Postgres filesystem —
  the E15 caveat answered); kill path; replay to target; measured RPO window v2; Q-batch: also
  CI-ify (dispatch job) and/or wire into release.yml (budget evidence decides).
- **S3 — k6 honest run**: same script, spine ON via demo overlay + default limiter limits; published
  BESIDE the 414 rps baseline (two-row comparison table); hardware + commit disclosed. This number
  is the M5 gate-threshold seed (D4) — recorded as such, no gate now.
- **S4 — Limiter posture**: per-instance buckets today (canary doubles quota). Q-batch decides:
  (a) documented posture (per-instance, quotas quoted per replica) OR (b) shared-store limiter —
  noting M5 brings Redis, so (b) may be explicitly deferred to M5 with rationale. Either closes the
  catch; no silent carry-over.
- **S5 — dependabot.yml**: weekly schedule, grouped minor/patch, production-deps focus; existing
  OWASP/Trivy bites stay the enforcement layer. Noise budget: one review, then grouped PRs.
- **S6 — Truth sweep + flip + citation (+ v1.1.0 tail if Q3=end)**: README/observability/runbook
  truth pass (Alertmanager real, PITR v2 real, honest k6 number); CHANGELOG; flip E16 ✅; citation;
  silence.

## 3. Process

1. Q-batch first (items flagged: amtool stub, PITR CI-ification, k6 scenario, limiter path,
   dependabot grouping, v1.1.0 position is owner-side — arrives with the package).
2. **Block 1 (S0–S3)** → STOP → channel audit → **Block 2 (S4–S6)**.
3. Evidence verbatim; her self-audit records attach to handoffs (ruling §1.7).

## 4. DoD

- [ ] v1.1.0 shipped (position per owner) — release run green, assets+digest verified
- [ ] Alertmanager live in metrics profile, `amtool` in CI
- [ ] PITR v2 with off-disk WAL, measured RPO (+ CI disposition per Q-batch)
- [ ] Honest k6 (spine ON, default limits) published beside 414 rps
- [ ] Limiter posture closed either way
- [ ] dependabot.yml live
- [ ] Truth sweep + flip → citation → nothing after

Then the channel declares E16 CLOSED — and M5's package is waiting.
