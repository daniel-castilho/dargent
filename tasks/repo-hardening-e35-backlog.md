# Repo Hardening E3.5 — Backlog

## Epic E3.5 — Agent Engineering & Repository Hardening (the Akita pass)

**Priority:** P1 (small, high leverage; runs alongside E3 — no file overlap, see spec §3)
**All stories:** Must
**Companions:** `repo-hardening-e35-spec.md` · `repo-hardening-e35-implementation-sequence.md` · `ai-software-engineer-prompt-repo-hardening-e35.md`

**Execution status:** opened 2026-08-29. Origin: akitaonrails.com knowledge survey (see spec §4 sources).
This epic is **governance-first**: the deliverable is rules written into the governing docs + operational
protection (branch settings, backup, compose limits). Zero application code. All stories start ☐.

---

## Epic outcome

The method becomes survivable and self-enforcing: `main` cannot be force-pushed or deleted away; the repo
replicates itself to a mirror with one command; agent-readability conventions (file size, grep-test names,
provenance comments, headless tests) are **written into coding-standards and AGENTS.md** and therefore bind
every future epic; the single-host compose fleet cannot be OOM'd by its own canary; and the build-once /
few-workers principles live in design.md where E6/E14 will inherit them.

---

## Story map

```text
BASELINE
S0   Inventory: Makefile, compose files, CI jobs, current protection state

PROTECTION
S1   Branch protection on main (force-push/deletion block) + public-API evidence
S2   Backup mirror script + idempotency proof

GOVERNANCE (the core — rules that bind from now on)
S3   Verbatim inserts: coding-standards §1.1, AGENTS 4.6 + §6 row, design §7.4/§11.1/§12

RESOURCES & SURFACE
S4   Compose limits: measure → set → validate → headroom assert
S5   Makefile standardization + README quickstart one-command path

CLOSURE
S6   Matrix, ledger E3.5 ✅, CHANGELOG, lessons candidate, coordination with E3
```

---

## S0 — Inventory ☐

### Work
- [ ] Makefile exists? List current targets verbatim (extend-only later)
- [ ] Compose file path(s) + full service list; compose version on host (`docker compose version`)
- [ ] CI job names (via runs API) — informational only (required checks are OUT of scope, spec §5.1)
- [ ] Current protection state: `GET /repos/daniel-castilho/dargent/branches/main` → expect `"protected": false` (before evidence)
- [ ] Read spec §5 blocks end to end (they are insert-ready; do not author variants)

### Acceptance
- [ ] Inventory recorded in the matrix; no open questions (ask, don't guess)

## S1 — Branch protection ☐

### Work
- [ ] Apply §5.1: block force pushes + deletions on `main`, include administrators, NO required checks
- [ ] Preferred path: owner applies via UI (2 min); engineer may attempt `gh api -X PATCH` with admin scope
      — if scope is missing, stop and hand the owner the exact procedure
- [ ] After: public API shows `"protected": true` → cite response in matrix (after evidence)

### Acceptance
- [ ] `protected: true` evidenced; no required checks enabled; procedure written down

## S2 — Backup mirror script ☐

### Work
- [ ] `scripts/backup-mirror.sh` per spec §5.2 (mirror clone → remote update; optional secondary remote;
      never prunes; no credentials; nonzero exit on failure)
- [ ] Prove: run 1 (clone) + run 2 (update, "not re-cloned") into a temp dir; `rev-parse HEAD` parity with origin
- [ ] Negative check: script contains no tokens/passwords; does not reference pruning flags

### Acceptance
- [ ] Idempotency proven and quoted in the matrix; script committed executable

## S3 — Governance inserts ☐ (the core deliverable)

### Work
- [ ] coding-standards.md: insert §1.1 verbatim (spec §5.3)
- [ ] AGENTS.md: insert rule 4.6 verbatim (§5.5) + §6 checklist row verbatim (§5.4)
- [ ] design.md: §7.4 relay-workers bullet (§5.7) · §11.1 build-once bullet (§5.6) · §12 limits bullet (§5.8)
- [ ] Diff review: inserted text matches the spec blocks byte-for-byte (no paraphrasing policy)

### Acceptance
- [ ] All six blocks installed verbatim; anchors in the right sections; nothing else touched in those files

## S4 — Compose resource limits ☐

### Work
- [ ] Bring the fleet up; record `docker stats --no-stream` baseline (matrix)
- [ ] Apply §5.9 table adjusted within ±50% of measurements; host-headroom assert (Σ ≤ ~75% RAM, `free -h`)
- [ ] `docker compose config` validates; after re-up, stats show the caps; fallback key set if the host
      compose rejects `deploy.resources.limits` (documented choice, not silent)

### Acceptance
- [ ] Every service limited; validation + post-cap stats cited; no service left unlimited

## S5 — Makefile + README quickstart ☐

### Work
- [ ] Targets per spec §5.10 (`verify`, `build`, `up`, `down`, `backup`, `scope-check`) — extend-only
- [ ] Run every pre-existing target once (nothing broken) + each new target once
- [ ] README: quickstart one-command path (`make up` / `make verify`), backup cron note; prerequisites stay honest

### Acceptance
- [ ] `make verify` green; `make scope-check` emits the boundary checks; README truthful

## S6 — Closure ☐

### Work
- [ ] `tasks/e35-acceptance-matrix.md` filled (every §5 contract row evidenced)
- [ ] Ledger E3.5 → ✅; CHANGELOG; lessons candidate: the required-checks-vs-direct-push interaction (§5.1)
- [ ] Coordination: E3.5 closes BEFORE E3's closure commit (or ledger flip rebased) — spec §3
- [ ] Final commit: `chore(repo): harden agent engineering — branch protection, backup, readability rules, compose limits`

### Acceptance
- [ ] Matrix zero pending; docs truthful; zero diffs under `modules/*/src`, `apps/*/src`, `.github/`
