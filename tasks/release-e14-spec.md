# E14 — Spec: Release Engineering & Restore Drill (v1.0.0)

Epic row: `| E14 | Release engineering & restore drill | repo, docs | E12, E13 | M4 | ☐ |`
Binding context: `docs/release-runbook.md` §1 (artifacts & promotion), §2 (pre-release checklist),
§6 (backup/restore/drills) + the runbook's own correction rule ("fix it in the same PR that discovered
the fact"). Companion: `release-e14-backlog.md` (step detail), `release-e14-sequence.md` (blocks/PRs).

---

## §1 Objective

Ship **v1.0.0** as a fully evidenced artifact chain — annotated tag → green gates on the tagged commit →
immutable semver image on GHCR → GitHub Release with jar + CycloneDX SBOM of the exact shipped image —
with the restore path proven by a recorded drill (measured RTO), and every line of runbook §1/§2/§6
true in the same epic that exercises it.

## §2 Non-goals (scope fence — P3 discipline)

- **DEBT-7 consolidation: OUT** (freeze discipline; window stays post-v1.0.0).
- Tracing, rate limiter (E15), any new runtime dependency or env var: OUT.
- No gate rewiring beyond what S0's one-line lint widen already adjudicates; floors untouched
  (owner-fixed 70/75/80/50/40 LINE).
- No `deploy.sh`/`rollback.sh` behavior changes (they are drilled and corrected as of E12; §3–§5 are
  not this epic's surface except where §1's org-name truth pass touches the runbook text).
- No host-side operations (systemd units SHIP as files; applying them to the on-prem host is the
  operator's act, outside CI).
- Maven version stays `1.0-SNAPSHOT` (runbook §1 explicit) — no pom bumps anywhere, ever, in this epic.

## §3 Contracts inherited & honored

| Contract | Source | Disposition in E14 |
|---|---|---|
| Tag immutability; fix-forward via v1.0.1 | spec §3.1 (new, carved in prompt) | binding |
| Gates never skip the release path | E13 gate suite | release.yml re-runs full suite |
| SBOM of the exact image digest | runbook §1 | digest in Release body + SBOM attached |
| Citation = 1 commit after flip; nothing after | E11–E13 precedent | S8 sequence carved in sequence.md |
| Floors owner-fixed; silent lowering = P1 | E13 adjudication 2026-09-06 | untouched |
| Evidence lint covers backtick-quoted `3[0-9]{9,}` | E13 closure adjudication 2026-09-07 | lands in S0 |
| New runtime env vars: NONE | spec §4 discipline | CI-side `packages: write` only |
| Her Q-batches BEFORE execution; attribution conflicts asked | DOD §2, post-TD-30 | standing |
| epics E14 milestone cell → `post-M4 (cuts v1.0.0)` | owner 3/3 intake adjudication 2026-09-07 | S0 docs-only |
| compose.demo.yaml overlay + README line | owner 3/3 intake adjudication 2026-09-07 | S6 rider |
| Alerts / k6 number / webhook rate limit / PITR drill: ALL deferred | owner 3/3 intake adjudication 2026-09-07 | E15/M5; body cap joins DEBT-8 wording (S0) |
| E9 inventory = **23** anchored (1/6/2/10/3/1) — TD-34: "22" was channel sum slip; "34" = stale pre-TD-31 guide | Q1 adjudication 2026-09-07 (tree-verified twice) | S0: row total 23/23 + guide sync |
| republish-outbox = Proposal A (script + 1 compose line + §6 TRUE) | Q2 adjudication 2026-09-07 | S3 conditions (cap → non-zero; fail-closed preconditions) |
| Registry push: sha- AND edge **main-only**; PRs never push | Q3 adjudication 2026-09-07 | S1 step condition |

## §4 New configuration surface

- CI: `permissions: packages: write` (release + image push jobs); GITHUB_TOKEN only. **No PATs, no new
  org secrets.** If anything seems to need more → STOP + Q-batch (sequence.md abort #1).
- Runtime: **zero new env vars.** The app is not touched by this epic except tests/ITs if the drill
  requires a fixture hook (prefer zero app changes; use existing smoke/admin surfaces).
- Registry: `ghcr.io/daniel-castilho/dargent-api` (visibility = repo default; owner may flip later —
  not a blocker).

## §5 Acceptance matrix (fill at S5; each row needs run id + shortsha)

| Step | Deliverable | Evidence (run id / digest / file) | Status |
|---|---|---|---|
| S0 | lint `3[0-9]{9,}` green, count N | 89 ids, 0 violations (local relint, commit `7a416a3`; CodeQL `34140383190` green) | ✅ |
| S0 | governance docs synced, epics E9=23/23 + E14 cell `post-M4` + DEBT-8 body-cap word | PR #5 merged `c2e58c3` (CI `34140383196` 6/6 green incl evidence-lint) | ✅ |
| S1 | sha+edge pushed on main | PR #6 merged `6c2e7a4`; push run `34143313416` image job green; GHCR versions: `sha-6c2e7a4`, `sha-f6d0303`+`edge` | ✅ |
| S2 | rc rehearsal green end-to-end | rc1 FAILED (notes heredoc — run `34145039268`, tag never moved); fix PR #8 merged `9d0ce22`; rc2 GREEN run `34148023947` | ✅ |
| S2 | Release assets jar+SBOM, digest match | Release `v1.0.0-rc2`: GHCR `1.0.0-rc2` = `sha256:344a4f0bf9bf90562010261f48ca33e7afd976df2f49f0d6e3b86e5b483f6c7f` == body digest; asset jar sha256 `9b412908bceb9eabdc30088eeefdc7b280ab79732e7cfd9b4bd4a7650834711d` == image-extracted jar | ✅ |
| S3 | backup/restore scripts + negative test ≠0 | local: tampered manifest → `MISMATCH expected=14 actual=13` → exit 1; republish 4/4 paths (unset→1, relay-off→404-reason, happy→0, 501-window→1) — PR #9 merged `e1132ea` | ✅ |
| S4 | restore-drill green + record w/ measured RTO | CI `restore-drill` job green: run `34155211811` (job `101847517958`), `DRILL RESULT: PASS — RTO 21s (≤ 1800s)`; record `docs/drills/restore-2026-09-07.md` (local 23s + CI 21s + negative paths) | ✅ |
| S5 | two-release migration verdict | `docs/releases/v1.0.0-migration-review.md` — **PASS with two dispositions** (10/11 expand-only; F1 resolved by disposition: V301 unchanged, direct v0.3.0→v1.0.0 UNSUPPORTED, data-only path proven by rehearsal). Owner adjudicated "F1 round 2 — A'" (Emenda (e), evidence-over-will) | ✅ |
| S6 | releases/v1.0.0.md + CHANGELOG + README | `docs/releases/v1.0.0.md` (capabilities M0–M4+E14, architecture, ops, risks incl. F1, artifact map), CHANGELOG `[1.0.0]` section, README truth pass (E14 live wording + drill), playbook §6.1 S7 regression map, lessons #16–#18 | ✅ |
| S7 | RC regression all-green + freeze | RC `601a669` (post-F1-A'): push CI run #206 `34178448326` green (build+image+Trivy+runtime-smoke); dispatch run #207 `34179487749` green incl. proof-daily + restore-drill | ✅ |
| S8 | tag → release → verify → flip + citation | tag `v1.0.0` (annotated) @ `601a669`; release run #3 `34180455833` GREEN (gates+restore-drill+release); digest `sha256:9cc760dc…f291f46` == Release body == SBOM purl `pkg:oci/dargent-api@sha256:9cc760dc…`; asset jar `fadeb35e…` == image-extracted jar bit-for-bit; flip (epics E14 ◐→✅) + citation (release run id @ tag + flip run id) via PR | ✅ |
| S8 | tag v1.0.0 → release run → flip → citation | | ☐ |

## §6 Evidence policy

- Run ids verbatim (post-evidence-lint they are also mechanically checked — the gate watches this epic).
- Drill record = text artifacts (logs, manifests, timings) in `docs/drills/restore-<date>.md`.
- Negative paths proven at least once (restore mismatch → non-zero; migration-gate covered by TD-33
  tests already).
- If evidence and prose disagree, evidence wins and the prose is fixed in the same PR (runbook rule,
  now a project-wide rule).

## §7 Post-E14 (channel-side, not engineer scope)

1. Channel declares **E14 CLOSED + v1.0.0 SHIPPED** after the audit (green AND coherent).
2. **Maturity re-assessment protocol fires**: the predictions registered 2026-09-02 in
   `internal-notes/maturity-assessment.md` are scored against the same rubric, with E11–E14 artifacts
   as evidence. Predictions that missed are analyzed (over/under-call), not quietly adjusted.
3. Owner queue afterward: PITR live drill (optional), NVD key registration (still open, zero-code
   upgrade when done), governance landing is absorbed by S0 of this epic (queue item #2 retires).
