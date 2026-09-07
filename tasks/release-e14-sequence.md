# E14 — Execution Sequence

Blocks, STOP gates, PR pairing, commit discipline. Standing rules apply (Q-batch before execution;
attribution conflicts are asked, never self-resolved; heredoc hygiene per TD-33 lesson — keep scripts
lean, retry idempotent).

---

## Block map

| Block | Steps | Theme | Ends with |
|---|---|---|---|
| 1 | S0–S4 | Make §1/§6 true: lint widen + governance, GHCR push, release workflow (rc rehearsal), backup/restore machinery, THE DRILL | STOP → channel audit |
| 2 | S5–S8 | Cut the release: checklist §2, release docs, RC regression + freeze, tag → flip → citation | v1.0.0 shipped + declared |

## PR pairing (1 per step/pair)

| PR | Steps | Contents | Expected CI |
|---|---|---|---|
| #5 | S0 | evidence-lint widen + governance docs sync | gates + evidence-lint (new count) |
| #6 | S1 | GHCR push on main + runbook §1 org truth | gates + image push (first sha/edge on main) |
| #7 | S2 | release.yml + rehearsal `v1.0.0-rc1` | gates on PR; release workflow on the rc tag |
| #8 | S3+S4 | backup.sh, restore.sh, systemd units, restore-drill job + drill record | gates + restore-drill |
| #9 | S5+S6 | checklist execution + §6 sweep + release docs + CHANGELOG + README | gates |
| — | S7 | RC regression (no new PR: it runs on PR #9's head after merge) | full suite + proof-daily dispatch + restore-drill + runtime-smoke |
| #10 | S8 | flip + citation (2 commits, 1 PR, merged only after release verified) | CI on flip + citation pushes |

Sequencing notes:
- S1 before S2 (release.yml pushes the semver image through the same auth path proven by S1).
- S3/S4 can start in parallel with S2's rehearsal **only after** S1 merges (registry exists).
- The rehearsal tag (`v1.0.0-rc1`) is cut from the merge-base commit that carries release.yml — NOT
  from Block 2's docs commits. It rehearses the workflow, not the release.
- S7's freeze is declared in the PR #9 merge body and holds until S8 completes.

## Commit discipline

- Conventional commits, as in E12/E13 (`feat(release):`, `fix(ci):`, `docs(release):`, `chore(ci):`).
- The drill record commit is a docs commit but carries evidence artifacts — keep artifacts in
  `docs/drills/` as text (verbatim outputs), never screenshots.
- Flip = last content commit; citation = exactly ONE commit after; **nothing after the citation** —
  validation runs (dispatch) are fine, tree changes are not.
- If anything fails late (tag run red, asset missing): the tag is NOT moved. Fix forward: new rc tag
  (`v1.0.0-rc2`) → green → only then cut `v1.0.0` fresh. A red release run on `v1.0.0` itself means
  the release is yanked and re-cut as `v1.0.1` after the fix — painful by design (contract §3.1).

## Citation contract for E14 (carved now, executed at S8)

`docs/epics.md` E14 row, citation clause:

> citation: tag `v1.0.0` on `<rc-shortsha>` shipped by run `<release-run-id>` (image `<digest>`,
> assets: jar + SBOM); flip `<flip-shortsha>` green on run `<flip-run-id>`

## STOP / abort conditions (any → halt + report, no improvisation)

1. Release workflow needs anything beyond GITHUB_TOKEN (a PAT, an org secret) → STOP, Q-batch.
2. GHCR package invisible/forbidden after S1 → STOP (visibility Q-batch), do not switch registries.
3. Restore drill cannot meet ≤ 30 min RTO on CI hardware → do NOT fudge the measurement; record the
   real number + the host-vs-CI caveat; RTO is a stated-honestly number, not a target to hit.
4. Any gate red at RC → freeze holds until green; no gate is waived for the release date.
5. Any instruction conflict (real or alleged) → ask, quote the source, wait (post-TD-30 rule).
