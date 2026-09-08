# E15 — Operational Hardening: alerts, load baseline, scale restore, PITR, webhook abuse controls, DEBT dispositions — COMMISSIONING PROMPT

> Channel: owner word "E15" (2026-09-08). Status: COMMISSIONED. Engineer confirms receipt + sends
> Q-batch BEFORE executing (DOD §2); attribution conflicts are ASKED, never chosen.
> Companion files: `ops-e15-backlog.md` · `ops-e15-sequence.md` · `ops-e15-spec.md` ·
> `ops-e15-execution-prompt-block1.md`. Precedents: E11–E14 packages (pattern unchanged).

---

## 0. Where the project stands

- **v1.0.0 SHIPPED** (tag @ `601a669`, run #3 `34180455833` gates+restore-drill+release, digest
  `9cc760dc…f291f46` Release-body == GHCR). **M0–M4 ✅. E14 CLOSED.** Post-1.0.0 era begins.
- External baselines registered: 5.3 → 6.3 → **6.9** (third autopsy). Its verdict frame is this
  epic's mandate: **"v1.0.0 é versão de engenharia; sem alerta e restore-feio não é versão de
  operação."** E15 is exactly movers (2)+(3)+(4)+(5) of that analysis, plus the adjudicated debts
  whose window the owner opened.

## 1. The epic in one sentence

**Close the gap between "engineering version" and "operations version": in-app webhook abuse
controls (DEBT-8 real closure), alert rules that fire and are tested firing, a published load
number, a restore drill at production-like scale, a PITR rehearsal — and dispose DEBT-7 honestly.**

## 2. Hard contracts (inherited + carved)

1. **Gates untouched** — floors owner-fixed (70/75/80/50/40), Trivy/OWASP/SpotBugs/evidence-lint
   unchanged. E15 adds checks (promtool), never relaxes one.
2. **Alert rules without a firing test don't exist** — every rule ships with a `promtool test rules`
   case proving it fires on a real condition and one proving it stays quiet on a healthy one.
   CI runs promtool; an untested rule fails the build.
3. **DEBT-8 closes in the app, not on a wish** — rate limit + body cap as in-app controls on
   `POST /webhooks/psp`; the NGINX wording becomes defense-in-depth; AGENTS §8 row → RESOLVED
   with the commit; threat model surface-1 updated in the same PR.
4. **Load numbers are published, never promoted to gate** — consultative per M5 stance; hardware
   disclosed; no k6 in per-push CI.
5. **Restore scale must be honest** — the scale drill uses bulk-seeded realistic volume (10k–100k
   journal lines class); the drill doc's own caveat ("36 KB dump is a seed") is the target being
   answered. Measured RTO at scale, never extrapolated.
6. **PITR rehearsal = measured RPO, not a paragraph** — base backup + WAL replay to a target time,
   validated with counts + balance proof; documented in `docs/drills/`.
7. **New runtime env vars are allowed this epic — each with rationale, safe defaults, and a line in
   the spec §4 table.** Tests tune limits explicitly; defaults must not affect demo/smoke paths.
8. **Chain discipline (unchanged)**: Q-batch first; 1 PR per step/pair; flip = last content commit;
   citation = exactly ONE after; nothing after the citation. **Closure audit verifies EVERY
   adjudicated deliverable against the tree — including yaml-only riders (TD-35 lesson, now
   institutional).**

## 3. Steps (detail in backlog; blocks in sequence)

- **S0 — Post-release truth pass** (the TD-35 closer): `docker/compose.demo.yaml` (spine ON) +
  README demo line; README cover tense fix ("cuts v1.0.0" → "cut v1.0.0 @ tag", L177/L187 class);
  mint the **E15 row** in `docs/epics.md` (☐, milestone cell `post-1.0.0`); verify the unconfirmed
  "104 ids" claim from the E14 citation PR body and correct if wrong; report current evidence-lint
  scanned-id count.
- **S1 — Webhook abuse controls (DEBT-8 real closure)**: in-app rate limit + body cap on the public
  webhook route; ITs prove 429/413 paths AND that HMAC still fails closed ahead of them; AGENTS §8
  DEBT-8 → RESOLVED; threat model + runbook wording updated same-PR.
- **S2 — Alert rules + (rider) Grafana as code**: Prometheus rule file(s) for the operations
  signals (proof-fail, outbox lag, DLQ depth, signature-failure burst, EXHAUSTED backlog — exact
  set via Q-batch), each with promtool firing/quiet tests wired into CI; Grafana provisioning
  (dashboards-as-code, metrics profile) as the optional rider — **no Alertmanager** (pager stays
  trigger-activated, E16 candidate).
- **S3 — k6 money-path baseline**: script + one published run in `docs/load-test-baseline.md`
  (p95 for create/pay/confirm vs the SLO buckets; hardware + date disclosed; consultative).
- **S4 — Scale restore drill**: dispatch-only job seeding realistic volume via bulk SQL + a handful
  of API txns → dump → destroy → restore → proof; RTO at scale recorded in `docs/drills/`.
- **S5 — PITR rehearsal**: base backup + WAL archive → restore to target time → counts + proof;
  measured achieved-RPO window; `docs/drills/pitr-<date>.md`. Local/documented first; CI-ification
  is a her-call rider.
- **S6 — DEBT-7 disposition (window is open)**: EITHER consolidate the `postJournal` twins with
  proof (full suite + floors identical + the E8/E9 guarantees re-evidenced) OR close the row as
  **won't-fix-by-design** with the original deliberateness rationale on record. No third option —
  no silent carry-over.
- **S7 — Docs sweep + flip + citation**: threat model/README/runbook truth pass; CHANGELOG
  `[Unreleased]` consolidated; then flip E15 ✅ → citation → silence. Tag (v1.1.0) is an owner call
  AFTER the epic — not in scope.

## 4. Process (standing flow — unchanged)

1. Engineer sends Q-batch first (expected items are flagged inline in the backlog: rate-limit
   algorithm & limits, rule set scope, Grafana rider scope, PITR CI-or-local, DEBT-7 path, E15 row
   wording). Channel adjudicates; answers carved into register + spec addendum BEFORE execution.
2. **Block 1 (S0–S3)** → STOP → channel audit (fast turnaround) → **Block 2 (S4–S7)**.
3. Evidence verbatim (run ids + shortshas); validation runs OK post-citation, tree changes are not.

## 5. DoD of the epic (matrix lives in the spec)

- [ ] S0 demo overlay live + README cover true + E15 row minted + id-count claim resolved
- [ ] S1 webhook 429/413 in-app, DEBT-8 RESOLVED end-to-end (code + ITs + AGENTS + threat model)
- [ ] S2 rules fire & quiet proven via promtool in CI (+ Grafana rider if taken)
- [ ] S3 load baseline has a real number with hardware disclosure
- [ ] S4 restore at scale recorded with measured RTO
- [ ] S5 PITR rehearsal documented with measured RPO window
- [ ] S6 DEBT-7 resolved either way, honestly
- [ ] S7 truth sweep + flip → citation → nothing after

**Then the channel audits, declares E15 CLOSED — and the composite question "versão de operação?"
faces the evidence with the artifact on the table.**
