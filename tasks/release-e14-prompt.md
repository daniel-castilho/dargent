# E14 — Release Engineering & Restore Drill (v1.0.0) — COMMISSIONING PROMPT

> Channel: owner adjudication 2026-09-07 ("package do E14 — prepare as documentações do próximo épico").
> Status: COMMISSIONED. Engineer confirms receipt + sends Q-batch BEFORE executing (DOD §2).
> Companion files: `release-e14-backlog.md` · `release-e14-sequence.md` · `release-e14-spec.md` ·
> `release-e14-execution-prompt-block1.md`. Precedent packages: E11/E12/E13 (pattern unchanged).

---

## 0. Where the project stands

- **M0 ✅ · M1 ✅ · M2 ✅ · M3 ✅ · M4 ✅** — E13 CLOSED (flip `65ec000`, citation `d36dd40`, tip).
  E14 is the **last ☐ row in `docs/epics.md`** and the only thing between main and **v1.0.0**.
- Everything a release needs already exists and is green on every push: full gate suite (SpotBugs,
  Checkstyle/Spotless, JaCoCo floors, OWASP, Trivy 2-pass + SBOM, CodeQL, Dependency Review),
  evidence-lint (85 ids resolving), readiness whole, ledger-admin segregated, proof-daily proving the
  ledger nightly, blue-green deploy + rollback + smoke drilled (runbook §3–§5, drills D1–D6 + D2'–D4').
- What does NOT exist yet: **nothing has ever been pushed to a registry** (no GHCR refs anywhere), there
  is **no release workflow**, and the runbook's own §1/§6 promise artifacts that aren't in the tree
  (see §2 below — the honest gap list).

## 1. The epic in one sentence

**Make `docs/release-runbook.md` §1 (artifacts & promotion), §2 (pre-release checklist) and §6
(backup/restore/drills) TRUE — then cut annotated tag `v1.0.0` and ship the GitHub Release with the
jar + the SBOM of the exact image.**

The runbook is the binding contract, including its own rule: *"If a procedure here is wrong, fix it in
the same PR that discovered the fact."* E14 does not invent a release process; it implements the one
already written, and truth-passes the lines that were aspiration.

## 2. The honest gap list (recon 2026-09-07, tree @ `d36dd40`)

| Runbook says | Tree has | E14 action |
|---|---|---|
| §1: every main commit → `ghcr.io/<org>/dargent-api:sha-<short7>` (immutable) + `:edge` | No registry push at all (only `ci.yml` + `codeql.yml` exist) | Implement push on main; replace `<org>` with `daniel-castilho` |
| §1: tag `vX.Y.Z` → gates re-run on tagged commit, semver image pushed, GH Release with jar + CycloneDX SBOM **of the exact shipped image** | No release workflow | `.github/workflows/release.yml` + rehearsal tag `v1.0.0-rc1` |
| §2: migration two-release review (N+1 keeps N runnable, vice versa) | Never performed for v0.3.0→v1.0.0 | Execute + record verdict in the release doc |
| §2 item 4 / §6: restore drill **current**; "the drill is the deliverable" | No `scripts/backup.sh`, no `scripts/restore.sh`, no `deploy/systemd/dargent-backup.*`, no `docs/drills/` | Build the chain + CI `restore-drill` job + drill record with measured RTO |
| §6: `scripts/republish-outbox.sh --from <ts>` | **Does not exist** | Implement if trivial via existing admin/outbox surface, else fix §6 in the same PR — her Q-batch decides with evidence |
| §1: "Maven version stays `1.0-SNAPSHOT` in dev" | Parent pom = `1.0-SNAPSHOT` | **NO version bump anywhere. Release identity = tag + image. This contract is honored, not changed.** |

## 3. Hard contracts (cravados — identical policy to E13)

1. **Tag immutability**: annotated tags are never moved, re-pointed or deleted. A bad v1.0.0 is fixed
   forward by `v1.0.1`; there is no re-cut.
2. **Gates never skip the release path**: the release workflow re-runs the FULL gate suite on the tagged
   commit. "It's just the release" is not a reason to drop Trivy/OWASP/floors — it is the reason they exist.
3. **SBOM of the exact image**: the GitHub Release attaches the CycloneDX SBOM generated from the image
   **digest actually pushed**, and the digest is written into the Release body. An SBOM of a sibling
   build is a fiction — TD-class defect if attempted.
4. **Freeze**: after the RC commit is tagged, no content commits until flip+citation. Release notes and
   docs land BEFORE the tag; the flip (epics ✅) lands AFTER the release is verified green.
5. **Chain discipline (unchanged)**: 1 PR per step/pair; flip = last content commit; citation = exactly
   ONE commit after flip; nothing lands after the citation. Evidence = run ids + shortshas, verbatim.
6. **Floors are owner-fixed** (70/75/80/50/40 LINE) — untouched here; release changes no threshold,
   no exclusion, no gate wiring.
7. **No new runtime env vars.** CI-side only: `permissions: packages: write` (GITHUB_TOKEN), no PATs.

## 4. Adjudicated in this package (channel rulings, 2026-09-07)

- **E13 aftermath lands here as S0**: evidence-lint pattern widened to backtick-quoted `3[0-9]{9,}`
  (owner adjudication at E13 closure — GitHub ids rolled into `34…`); one-line change + green re-run.
- **Governance landing rider in S0**: in-repo `docs/handoff-dod.md` + `docs/governance-commit-guide.md`
  synced with the corrected workspace copies (§1 anchored `@Test$`; E9 row count corrected to **22**;
  E9 totals), epics E9 row corrected. Docs-only commit(s), evidence-lint green.
- **DEBT-7 stays OUT of E14** (freeze discipline: no ledger-internal consolidation inside the release
  window). Target remains post-v1.0.0.
- **Rehearsal tag `v1.0.0-rc1` is mandatory** before the real tag: the release workflow's first live
  execution must happen on a throwaway semver prerelease, not on the artifact of record.
- **Backup scope**: CI drill proves the **logical path** (`pg_dump -Fc` → restore → verify → proof) with
  measured wall-clock RTO. WAL/PITR archival ships as host units + docs but is NOT CI-drilled (declared
  honestly in the drill record). PITR live drill = post-v1.0.0 owner call.
- **External-analysis intake amendments (owner 3/3, 2026-09-07 — triage in
  `internal-notes/external-analysis-2026-09-07.md`):**
  1. **Milestone-cell truth-pass in S0**: epics.md E14 row milestone cell `M4` → `post-M4 (cuts
     v1.0.0)` — the README already discloses the carve-out ("E14 cuts v1.0.0 as its own epic | ✅");
     this aligns the one stale cell so no artifact shows an open epic inside a ✅ milestone. Docs-only.
  2. **Demo overlay rider in S6**: `docker/compose.demo.yaml` (spine ON — RELAY/LEDGER_CONSUMER/
     RECONCILER/EXPIRATION `=true`) + one README line. Zero app/CI changes. Closes the verified
     "two-worlds" gap (bare `compose up` never settles).
  3. **Deferred list CONFIRMED**: alert rule, k6 published number, webhook rate limit (DEBT-8 stands,
     adjudicated 2026-09-07), PITR live drill — all stay E15/M5. **Body cap** joins the DEBT-8 row
     wording in S0 (docs-only, no code).

## 5. Process (standing flow — unchanged)

1. Engineer sends **Q-batch first** (anything ambiguous in this package — e.g. the republish-outbox
   question, GHCR package visibility, whether `:edge` push belongs on PR branches or main only).
   Channel adjudicates; answer is carved into the register + spec addendum BEFORE execution.
2. **Block 1 (S0–S4)** → STOP → channel audit (fast turnaround promised) → **Block 2 (S5–S8)**.
3. Attribution conflicts are ASKED, never self-resolved (DOD §2, post-TD-30 rule — the standard is set).
4. Every step lands as its own PR (or tightly-paired commits) with evidence in the PR body.

## 6. DoD of the epic (acceptance matrix template lives in the spec)

- [ ] S0 evidence-lint `3[0-9]{9,}` green (new count reported) + governance docs synced
- [ ] S1 main pushes `sha-<short7>` + `:edge` to GHCR; run evidence + package URL
- [ ] S2 `release.yml` proven on `v1.0.0-rc1`: gates green on tagged commit, semver image on GHCR,
      GH Release with jar + SBOM + digest
- [ ] S3 `backup.sh` + `restore.sh` + systemd units per §6; restore exits non-zero on any mismatch
- [ ] S4 CI `restore-drill` green; `docs/drills/restore-<date>.md` with dump age, verification output,
      measured RTO vs ≤ 30 min
- [ ] S5 pre-release checklist §2 executed (incl. two-release migration verdict `v0.3.0 ⇄ v1.0.0`)
- [ ] S6 `docs/releases/v1.0.0.md` + CHANGELOG 1.0.0 + README truth + lessons reviewed
- [ ] S7 full regression on RC (CI + proof-daily dispatch + restore-drill + runtime-smoke) green; freeze
- [ ] S8 tag `v1.0.0` → release run green → **flip → citation → nothing after**

**Then the channel declares E14 CLOSED + v1.0.0 SHIPPED — and the maturity re-assessment protocol
fires (predictions registered 2026-09-02 in `internal-notes/maturity-assessment.md` face the real
rubric; that is channel-side work, not engineer scope).**
