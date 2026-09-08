# E14 Block 1 — EXECUTION PROMPT (S0–S4)

You are executing Block 1 of E14 (Release Engineering & Restore Drill). Full package:
`release-e14-{prompt,backlog,sequence,spec}.md`. Send your Q-batch BEFORE starting if anything below is
ambiguous or conflicts with an instruction you hold. Attribution conflicts are ASKED, never chosen
(DOD §2). On completion: STOP and hand off — do NOT start Block 2 (S5–S8) without channel audit.

---

## Step S0 — evidence-lint widen + governance landing (1 PR: docs + 1 line)

1. `scripts/evidence-lint.sh`: `ID_RE='`33[0-9]{8,}`'` → `'`3[0-9]{9,}`'`; update the header comment
   (`33\d+` mention) to match. This is owner-adjudicated (E13 closure, 2026-09-07) — the GitHub id
   range rolled into `34…`; contract widened by the same process the gate enforces.
2. Sync in-repo copies with the corrected workspace versions provided by the channel:
   `docs/handoff-dod.md` (§1 anchored `@Test$` + new §4 TD-34 row), `docs/governance-commit-guide.md`
   (TD-34 sum fix — in-repo §2 is the stale pre-TD-31 "34" version), `docs/epics.md` E9 row total →
   **23/23** + E9 totals.
3. `docs/epics.md` E14 row: milestone cell `M4` → `post-M4 (cuts v1.0.0)` (owner adjudication
   2026-09-07 — README already discloses the carve-out; align the stale cell).
4. `AGENTS.md` §8 DEBT-8 row: add "**and request body cap**" to the NGINX-edge disposition. Wording
   only; no code.
5. Evidence: evidence-lint job green + NEW scanned-id count (was 85); `git diff --stat` docs-only.

## Step S1 — GHCR push on main (runbook §1 becomes true)

1. `ci.yml` image job: after both Trivy passes → push `ghcr.io/daniel-castilho/dargent-api:sha-<short7>`
   and `:edge` — **both ONLY on `push` to `refs/heads/main`** (Q3 adjudication: runbook §1 literal;
   PRs build locally for gates, never push). Step condition:
   `if: github.event_name == 'push' && github.ref == 'refs/heads/main'`. Job-scoped
   `permissions: packages: write`. GITHUB_TOKEN only. No PATs, no new secrets (anything else → STOP,
   sequence.md abort #1).
2. Runbook §1: `<org>` → `daniel-castilho` (same PR).
3. Evidence: first green main-push run id + shortsha; digest of the pushed sha-image verbatim;
   package URL.

## Step S2 — release workflow + rehearsal tag `v1.0.0-rc1`

1. `.github/workflows/release.yml`, trigger `push: tags: ['v*']` ONLY. In order: full `./mvnw clean
   verify` gates on the tagged commit (no shortcuts — contract) → build API image from the tag →
   Trivy 2-pass → push `ghcr.io/daniel-castilho/dargent-api:<semver-without-v>` → CycloneDX SBOM of
   the pushed **digest** → GitHub Release with jar + SBOM attached + digest written in the body.
2. Rehearse: cut **`v1.0.0-rc1`** on the commit carrying release.yml → workflow green end-to-end.
   Never move/delete a tag; failure → fix → `v1.0.0-rc2` (sequence.md discipline).
3. Evidence: rehearsal run id; `docker manifest inspect`/API digest pair (pushed == Release body);
   asset list verbatim.

## Step S3 — backup & restore machinery (runbook §6, verbatim behavior)

1. `scripts/backup.sh`: `pg_dump -Fc` + manifest (per-table counts, ΣDR/ΣCR, size, pg version, ts);
   rotation-friendly naming.
2. `scripts/restore.sh <dump>` per §6: fresh cluster → Flyway (no-op expected; pending migration =
   abort) → boot app → per-table counts vs manifest → balance proof (ΣDR=ΣCR, projection==lines) →
   non-zero on ANY mismatch; prints the go/no-go line; never starts traffic.
3. `deploy/systemd/dargent-backup.{service,timer}` + WAL archival (nightly, 15-min, rotation 8) —
   shipped as files; declared shipped-not-drilled in the record.
4. `scripts/republish-outbox.sh --from <ts>`: implement if ≤ ~40 lines on existing clients/admin
   surface; otherwise fix §6's line in this PR. Either way §6 ends TRUE — your Q-batch may propose
   which, with the tree evidence.
5. Evidence: full local log (backup → restore → PASS) + one negative run (tampered manifest → exit ≠ 0).

## Step S4 — THE DRILL (§6: "the drill is the deliverable")

1. CI job `restore-drill` (workflow_dispatch; wired into the release chain for S7/S8): seeded stack →
   deterministic money path → `backup.sh` → cluster destroyed → `restore.sh` → all assertions green.
   Wall-clock restore+verify.
2. `docs/drills/restore-<date>.md`: date, dump age, size, verification output verbatim,
   **measured RTO vs ≤ 30 min** (real number — CI-vs-host caveat if slower; never fudged), WAL/PITR
   shipped-not-drilled declaration.
3. Evidence: drill run id + the RTO line quoted in the PR body.

---

## Block 1 DoD (handoff format — precedent E12/E13)

- Verbatim `git log` chain + verbatim `gh run list` pairs (run id, number, conclusion, shortsha).
- New evidence-lint count. Digests + asset lists. Drill record path + RTO.
- Flags for channel decision (if any), non-closures declared, nothing self-served.
- **STOP after S4.** Block 2 (S5–S8: checklist §2, release docs, RC regression + freeze,
  tag `v1.0.0` → flip → citation → silence) is commissioned only after channel audit.
