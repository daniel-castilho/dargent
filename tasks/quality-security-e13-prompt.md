# E13 Prompt — Quality & Security Gates (the last M4 epic: discipline becomes mechanism)

**Milestone state at emission:** M0 ✅ · M1 ✅ · M2 ✅ · M3 ✅ · M4 ◐ (**E12 ✅ closed; E13 completes
M4**; E14 closes v1.0.0). E13 = the epic where every rule this project enforced by human discipline
becomes a machine gate — and where the author-side defect classes (TD-29/31/33) become CI failures.

## What this epic is

The design committed to these gates since M0 (`ci.yml` comments, README "Pipeline at M4", design §11).
Contracts, in authority order:
1. `docs/design.md` §11 — SpotBugs → OWASP Dependency-Check → combined coverage gate → Trivy (2-pass:
   SARIF advisory + HIGH/CRITICAL gate) + SBOM → CodeQL + Dependency Review. **OWASP + JaCoCo land
   TOGETHER** (owner directive, standing).
2. **Coverage floors 70/75/80/50/40** (owner-fixed set). Binding of each number to scope follows
   design.md §11 — if the design does not map them explicitly, STOP and ask (zero-from-memory applies
   to owner-fixed numbers too).
3. `docs/handoff-dod.md` — contractual. After this epic, its rules are ENFORCED by the evidence-lint
   job, not by audit (TD-31's permanent answer).
4. `AGENTS.md` §8 + register — debt rows (DEBT-7 consolidation was scheduled for the M4 refactor
   window; adjudication below).

## Pre-adjudicated (owner channel, 2026-09-06)

- **Style gates:** PMD OUT (SpotBugs covers its yield on modern Java); **Checkstyle via Spotless IN**
  (deterministic, idiomatic). ONE formatting-normalization commit is allowed BEFORE S0 lands the check
  (commit message = `style: spotless normalize (pre-gate)`; zero semantic diff, verified by `git diff
  --stat` review in the handoff).
- **Riders ALL IN:** (R1) **evidence-lint** — every run id cited in `docs/epics.md` + acceptance
  matrices must resolve via `gh api` and carry its number adjacent (pairs, not ids alone); job runs on
  PRs + nightly on main. (R2) **readiness-SQS/SNS indicators** — closes the Q25/design §9 gap:
  readiness group reflects queue + topic reachability via the EXISTING LocalStack-compatible clients.
  (R3) **ledger-admin segregation (N5)** — rebuild/proof/settlements behind
  `DARGENT_LEDGER_ADMIN_KEY` (SAME pattern as `DARGENT_OUTBOX_ADMIN_KEY`: env names an existing ACTIVE
  key; validation first; 404-hidden when unset; audit keeps real actor). (R4) **threat model executed**
  — `docs/security/threat-model.md`, STRIDE × surfaces (public webhook, admin surfaces, PSP seam,
  compose edge, CI), each cell: existing control (linked) or gap → new DEBT/TD row. Catalog scenario 28
  (lockdown) is ALREADY satisfied by E11 S3 `ProductionLockdownIT` — matrix gets the pointer, not new code.
- **Zero new envs except ONE:** `DARGENT_LEDGER_ADMIN_KEY` (R3; §4.1 adjudicated with this package).

## Blocks

- **Block 1** (this emission): S0 SpotBugs + Spotless/Checkstyle → S1 JaCoCo floors + OWASP
  (together) → S2 Trivy 2-pass + SBOM → S3 CodeQL + Dependency Review. Gates spine; every gate green
  on the epic's own chain.
- **Block 2** (after Block-1 audit): S4 riders R1+R2+R3 → S5 threat model (R4) + matrix/catalog update
  → **E13 ✅ flip + M4 ✅** (chain E11+E12+E13 cited) + citation.
