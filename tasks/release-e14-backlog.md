# E14 — Backlog (S0–S8)

Pattern identical to E11–E13: each step = one PR (or tight pair), evidence verbatim in PR body,
Q-batch precedes execution, STOP after Block 1.

---

## BLOCK 1

### S0 — E13 aftermath + governance landing (docs + 1-liner lint)

Deliverables:
1. `scripts/evidence-lint.sh`: `ID_RE='`33[0-9]{8,}`'` → `'`3[0-9]{9,}`'` (owner adjudication 2026-09-07:
   GitHub run ids rolled into the `34…` range; contract was literal-but-stale). Comment block updated to
   match. Job re-run green; **report the new scanned-id count** (was 85 — expect growth).
2. Governance sync, docs-only:
   - `docs/handoff-dod.md` ← workspace corrected copy (§1 anchored `@Test$` — TD-31 lesson).
   - `docs/governance-commit-guide.md` ← workspace corrected copy.
   - `docs/epics.md` E9 row: total corrected to **23/23** (anchored per-class 1/6/2/10/3/1 — the row's
     own fractions already sum to 23; "22/22" was a TD-31-correction arithmetic slip, logged TD-34;
     in-repo guide §2 is the stale pre-TD-31 "34" version — replaced by the workspace copy).
   - Totals of E9 reconciled per workspace copy.
3. No functional changes. All gates green.
4. `docs/epics.md` E14 row: milestone cell `M4` → `post-M4 (cuts v1.0.0)` (owner adjudication
   2026-09-07: README line 183 already discloses the carve-out — align the one stale cell so no
   artifact shows an open epic inside a ✅ milestone).
5. `AGENTS.md` §8 DEBT-8 row: add body cap to the disposition ("rate limit **and request body cap** —
   NGINX edge when a public deployment exists"). Wording only; no code.

Evidence: lint job run id + count; `git diff --stat` showing docs-only.

### S1 — Registry push on main (runbook §1 becomes true)

Deliverables:
1. `ci.yml` image job gains push steps: after Trivy 2-pass passes → push
   `ghcr.io/daniel-castilho/dargent-api:sha-<short7>` (immutable) and `:edge` (moving) — **BOTH
   main-only** (owner adjudication 2026-09-07, Q3: runbook §1 is literal — "every commit on `main`";
   PR/schedule/dispatch builds keep building images for the gates but NEVER push; PR heads are
   unvetted code, and fork PRs cannot write packages). Step condition:
   `if: github.event_name == 'push' && github.ref == 'refs/heads/main'`. Semver images come ONLY
   from release.yml on tags. `permissions: packages: write` scoped to the job; GITHUB_TOKEN only —
   **no PATs**.
2. `docs/release-runbook.md` §1: `<org>` → `daniel-castilho` (truth pass in the same PR).
3. psp-simulator image stays CI-local (§1 promises one shipped image: the API).

Evidence: first green run with push on main (run id + shortsha); GHCR package URL/visibility screenshot
or API output; a sha-image digest quoted verbatim.

Q-batch flag: package visibility (private-by-default vs public). Default: repo default; owner can flip.

### S2 — Release workflow + rehearsal tag (runbook §1 tag flow)

Deliverables:
1. `.github/workflows/release.yml`: trigger `push: tags: ['v*']` (+ nothing else). Jobs, in order:
   a. **Gates re-run on the tagged commit** — `./mvnw clean verify` (unit+ITs, SpotBugs, Spotless,
      floors, OWASP cached) — full suite, no shortcuts (contract §3.2).
   b. Build API image from the tagged commit; Trivy 2-pass on it.
   c. Push `ghcr.io/daniel-castilho/dargent-api:<semver-without-v>` (e.g. `1.0.0-rc1`, `1.0.0`).
   d. Generate **CycloneDX SBOM of the pushed image digest**; write the digest into the Release body.
   e. Create the GitHub Release for the tag: attach the API jar + the SBOM file; notes from the tag
      annotation + auto-generated changelog section.
2. **Rehearsal**: tag `v1.0.0-rc1` on the current tip-eligible commit → workflow green end-to-end →
   verify: image pullable by digest, Release assets present (jar + SBOM), digest in body matches.
3. The rehearsal tag is NEVER moved or deleted; if the rehearsal fails → fix → **new tag** `v1.0.0-rc2`.

Evidence: rehearsal run id + shortsha; asset list verbatim; digest pair (pushed vs Release body).

### S3 — Backup & restore machinery (runbook §6 implementation)

Deliverables:
1. `scripts/backup.sh`: `pg_dump -Fc` against the running stack + **manifest** (per-table row counts +
   ΣDR/ΣCR snapshot + dump size + pg version + timestamp) written alongside the dump; rotation-aware
   naming `dargent-YYYYMMDD-HHMMSS.dump`.
2. `scripts/restore.sh <dump>`: per §6 verbatim — restore into a **fresh cluster**, run Flyway
   (**no-op expected** — any pending migration = mismatch = abort), boot the app, verify per-table
   counts against the dump manifest, run the **balance proof** (ΣDR=ΣCR, projection==lines), exit
   non-zero on ANY mismatch. Traffic-release step prints the go/no-go line; the script never starts
   traffic itself (operator act), matching §6's "traffic never returns over an unverified restore".
3. `deploy/systemd/dargent-backup.{service,timer}` + WAL archival config shipped (nightly dump,
   15-min WAL, rotation keeps 8) — **host-side units, shipped not CI-drilled**; the drill record
   declares this honestly.
4. **Q2 ADJUDICATED (2026-09-07) — Proposal A adopted** (her recon: endpoint
   `POST /v1/outbox/republish` exists — `OutboxAdminController`, `{"from","to","types"}`, to exclusive,
   cap 500/call, responds `{"matched","republished"}`): implement
   `scripts/republish-outbox.sh --from <ts> [--to <ts>] [--types …]` + **one compose line**
   `DARGENT_OUTBOX_ADMIN_KEY: ${DARGENT_OUTBOX_ADMIN_KEY:-}` adjacent to line 78 (empty default keeps
   the route 404-hidden — R3 posture intact) + §6 line stays (becomes literal TRUE). Conditions:
   (i) print `matched` vs `republished` and **exit non-zero when matched > republished** (the 500/call
   cap must never under-republish silently); (ii) fail-closed preconditions with actionable messages
   (admin key unset; key without active row in `payments.api_keys` — precedent `ci-proof-daily:58-61`);
   (iii) relay OFF → WARN and continue (republished events persist until the relay runs — precedent
   `ci-proof-daily:40` for the ON case).

Evidence: local execution log (backup → restore → verify PASS); the non-zero path proven once by
negative test (tampered count → exit ≠ 0).

### S4 — THE DRILL (runbook §6: "the drill is the deliverable")

Deliverables:
1. CI job `restore-drill` (workflow_dispatch + included in the release chain before tagging):
   boot seeded stack → money path (N deterministic txns via smoke path) → `backup.sh` → destroy the
   cluster → `restore.sh` → assertions green. Wall-clock the restore+verify section.
2. `docs/drills/restore-<date>.md` drill record per §6: date, dump age, dump size, verification output
   verbatim, **measured RTO vs the stated ≤ 30 min**, and the shipped-not-drilled declaration for
   WAL/PITR.
3. Artifacts uploaded (drill log + manifest).

Evidence: drill job run id; the drill record's RTO line quoted in the PR body.

## STOP — BLOCK 1 AUDIT (channel). Turnaround fast, per standing promise.

---

## BLOCK 2

### S5 — Pre-release checklist execution (runbook §2, items 1–4)

Deliverables:
1. §2.1 matrix filled with evidence (this spec §6 filled as steps close).
2. §2.3 **two-release migration review**: `v1.0.0` migrations keep `v0.3.0`-built state runnable and
   vice versa — record the audit against the real V-table (expand/contract reasoning per migration
   since `v0.3.0`, incl. V110/V205/V207 verdicts under TD-33 policy). Verdict line goes into the
   release doc.
3. §6 full truth sweep: every claim vs tree (backup units exist, restore.sh exists, republish-outbox
   resolved, org name, WAL wording). Fixes in the same PR — the runbook's own rule.
4. `docs/testing-playbook.md` §6 pre-release regression list: name the scenarios that will run at S7.

Evidence: checklist ticked in the spec matrix; migration verdict verbatim.

### S6 — Release documents (runbook §2 item 2)

Deliverables:
1. `docs/releases/v1.0.0.md`: what ships (capabilities by milestone M0–M4, one line each with the epic
   that delivered it); architecture snapshot; ops entry points (runbook, runbooks/, slos, drills);
   **known accepted risks**: DEBT-7 (defer, post-v1.0.0), DEBT-8 (accepted for v1, NGINX edge when
   public), tracing (trigger-activated), PITR drill (shipped-not-drilled); two-release migration
   verdict; artifact map (tag, image, digest placeholder, SBOM, jar).
2. `CHANGELOG.md`: `## [1.0.0] - <date>` section (Keep-a-Changelog format, E0→E14 summary).
3. README final truth pass (badges/links to Release + GHCR once real).
4. Lessons reviewed (§2.2) — new lessons from E14 itself (e.g. anything the drill surfaced).

Evidence: file list + CHANGELOG section verbatim in PR.

### S7 — Pre-release regression on the RC commit + FREEZE

Deliverables:
1. RC commit = S6 head. All of it green ON THAT COMMIT: full CI (build+ITs+image+Trivy+SBOM),
   `proof-daily` workflow_dispatch, `restore-drill`, runtime-smoke, evidence-lint.
2. Freeze declared in the PR body: after this commit, only `tag → flip → citation`, nothing else.

Evidence: run ids + shortshas for each job on the RC commit.

### S8 — Cut v1.0.0 → flip → citation → silence

Order (verbatim sequence, no deviations):
1. `git tag -a v1.0.0 -m "Release 1.0.0: <one-line>"` on the RC commit; push tag.
2. Release workflow green (run id) → verify `:1.0.0` image + Release assets + digest match.
3. **Flip commit** (epics E14 → ✅ with run pairs; the ONLY content change): last content commit.
4. CI green on the flip push.
5. **Citation commit** (exactly ONE after flip): epics citation = release run id @ tag + flip run id.
6. Citation run green. **NOTHING lands after. Channel declares.**

Evidence: chain verbatim `git log`, run list verbatim, digest + asset list.
