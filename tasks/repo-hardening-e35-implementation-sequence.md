# Repo Hardening E3.5 — Implementation Sequence

## Epic E3.5 — Agent Engineering & Repository Hardening (the Akita pass)

**Companions:** `repo-hardening-e35-spec.md` · `repo-hardening-e35-backlog.md`
**Rule:** Complete each step's acceptance before the next. **This epic installs policy, it does not author
it** — the §5 blocks of the spec are inserted verbatim; paraphrasing a governance block is a defect.
**Process rule:** zero application code; the only executable artifacts are one shell script, a Makefile,
and compose keys — each proven by execution, not by belief.

---

## Global execution rules

1. Small conventional commits: `chore(repo): …`, `docs: …`, `feat(make): …` (story-sized).
2. No dependency additions; no changes under `modules/*/src`, `apps/*/src`, `.github/` — the scope check is
   structural (below) and runs before every push.
3. A red `main` baseline stops work (unchanged — and S1 is what makes it stick).
4. After each step: update backlog checkboxes, note deviations here.
5. Coordination with E3 (spec §3): disjoint file sets; E3.5's ledger flip lands first.

### Scope discipline check (run before every push)

```bash
git diff --stat main -- 'modules/*/src' 'apps/*/src' '.github' | wc -l   # expect 0 lines
bash scripts/check-boundaries.sh                                          # still green
```

### Fast verification used throughout

```bash
make verify        # once the Makefile target exists (Step 5); until then: mvn -B verify
```

---

## Step 0 — Inventory (S0)

### Actions
1. Makefile: exists? full target list verbatim. Compose: file path(s), service list, `docker compose version`.
2. CI job names via runs API (informational — required checks are out of scope by §5.1).
3. Protection before-evidence: `GET /repos/daniel-castilho/dargent/branches/main` → record
   `"protected": false` in the matrix.
4. Read spec §5.1–§5.10 end to end. The blocks are insert-ready.

### Done when
- Inventory in the matrix; zero open questions.

---

## Step 1 — Branch protection (S1)

### Actions
1. Attempt via `gh api repos/daniel-castilho/dargent/branches/main/protection -X PUT` with the §5.1 body
   (block force pushes + deletions, include admins, NO required checks). If 403/404 (scope), stop and hand
   the owner the exact UI procedure (Settings → Branches → rule `main`).
2. After-evidence: public API `"protected": true`, cited verbatim in the matrix.

### Done when
- `protected: true` public evidence; NO check requirements present; procedure recorded.

### Verify
```bash
curl -s https://api.github.com/repos/daniel-castilho/dargent/branches/main | grep '"protected"'
# expect: "protected": true
```

---

## Step 2 — Backup mirror (S2)

### Actions
1. Implement `scripts/backup-mirror.sh` per §5.2 (env-driven destination; clone-then-update; optional
   secondary remote; never prunes; no secrets; strict exit codes).
2. Prove: run 1 → clone; run 2 → update ("not re-cloned" in output); `git -C <mirror> rev-parse HEAD`
   equals the pushed head. Quote both runs in the matrix.
3. Review the script against §5.2 line by line (no pruning flags, no credentials, set -euo pipefail).

### Done when
- Idempotency + parity proven; script committed with the executable bit.

---

## Step 3 — Governance inserts (S3) — the core deliverable

### Actions
1. Insert the six §5 blocks: coding-standards §1.1 · AGENTS 4.6 · AGENTS §6 row · design §7.4 · §11.1 · §12.
2. Byte-check each insert against the spec (diff or careful review). Section anchors correct; **nothing
   else changes** in those files.
3. Sanity: the new rules cite `make verify` (Step 5 wires it) and the relay default N=4 (E6 inherits).

### Done when
- Six blocks verbatim; `git diff` of this step shows only the insertions.

---

## Step 4 — Compose limits (S4)

### Actions
1. Fleet up; record `docker stats --no-stream` baseline in the matrix.
2. Apply §5.9 (adjusted within ±50% of the measurements); headroom assert (Σ ≤ ~75% host RAM).
3. `docker compose config -q` validates; re-up; post-cap stats recorded. If the host compose rejects
   `deploy.resources.limits`, use the documented fallback keys and note the choice — never drop a limit.

### Done when
- Every service capped; validation + measurements cited; compose diff is limits-only.

### Verify
```bash
docker compose config -q && echo OK
docker compose up -d && sleep 10 && docker stats --no-stream
```

---

## Step 5 — Makefile + README (S5)

### Actions
1. Add/confirm targets: `verify`, `build`, `up`, `down`, `backup`, `scope-check` (§5.10) — extend-only.
2. Execute every pre-existing target once (nothing broken), then each new target once; `make verify` green.
3. README: quickstart gains the one-command path + backup cron note; prerequisites unchanged (honest).

### Done when
- All targets proven; README truthful; `make scope-check` reproduces the epic boundary checks.

---

## Step 6 — Closure (S6)

### Actions
1. Fill `tasks/e35-acceptance-matrix.md` (§5 contract → change → proof → evidence).
2. Ledger E3.5 → ✅; CHANGELOG; lessons entry if the protection/required-checks interaction taught something
   worth recording (candidate already flagged).
3. Final commit: `chore(repo): harden agent engineering — branch protection, backup, readability rules, compose limits`.

### Done when
- Matrix zero pending; scope check structural-clean; CI green on `main`.

### Verify
```bash
grep -n pending tasks/e35-acceptance-matrix.md    # expect: no output
git diff --stat main -- 'modules/*/src' 'apps/*/src' '.github' | wc -l   # expect 0
git status --porcelain                            # expect empty
```

---

## Failure playbooks (stop conditions)

| Symptom | Stop and do this |
|---|---|
| Temptation to enable required status checks on `main` | §5.1 is binding: GitHub rejects direct pushes to branches with required checks — it would freeze the marathon. Force-push/deletion block only |
| `gh api` returns 403/404 on protection | Missing admin scope. Hand the owner the exact UI procedure; gather public-API before/after evidence; do not loop retrying |
| Compose rejects `deploy.resources.limits` | Check `docker compose version`; apply the documented fallback keys; record the choice in the matrix. Never leave a service unlimited |
| `docker stats` empty / fleet down | Bring the fleet up first (Step 4 needs live measurements); never invent numbers |
| Mirror script would prune (mirror has prune-like refs behavior) | Plain `git clone --mirror` + `git remote update` never prunes by default — if you added a flag that prunes, remove it (§5.2 stop condition) |
| An insert "reads better" rephrased | It is policy, not prose: insert verbatim or stop and raise the change for sign-off (AGENTS.md preamble rule) |
| Makefile pre-existing target breaks | Revert your change to that target; extend below it; re-run the target to prove |
| E3 closure lands mid-epic | File sets are disjoint (spec §3) — only the ledger/CHANGELOG can collide: rebase the flip, note it in the matrix |
