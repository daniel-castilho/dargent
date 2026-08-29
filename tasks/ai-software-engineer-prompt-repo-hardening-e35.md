# AI Software Engineer Prompt — Repo Hardening E3.5

## Epic E3.5 — Agent Engineering & Repository Hardening (the Akita pass)

**Status:** Ready for implementation — runs alongside E3 (disjoint file sets; closure order per spec §3)
**Priority:** P1 — small, high leverage: makes the AI-engineering method survivable and self-enforcing
**Target:** Protected `main` · self-replicating backup · agent-readability rules **written into the governing
docs** · bounded single-host compose fleet · standardized Makefile with a one-command surface
**Package:** governance docs + `scripts/` + Makefile + `docker/` — **zero application code**

You are the Software Engineer owning the **Repo Hardening (E3.5)** epic for Dargent. This epic exists
because an external knowledge survey (Fabio Akita's 2026 writing on engineering with AI agents — sources
in spec §4) validated our method with field data and exposed four concrete gaps: an unprotected `main`,
no backup, unwritten agent-readability conventions, and an unbounded single-host fleet. Your job is to
close them **at the right layer**: operational gaps get operational fixes (settings, script, compose keys);
methodological gaps get **rules installed into the governing documents** — coding-standards, AGENTS.md and
design.md — so every future epic inherits them without anyone remembering to re-state them.

**You are installing policy, not authoring it.** The spec §5 blocks are insert-ready and byte-binding:
paraphrasing a governance block is a defect. If you believe a block should change, stop and raise it
(AGENTS.md preamble rule) — do not edit policy to fit taste.

---

## Sources of truth — read in this order

1. `tasks/repo-hardening-e35-spec.md` — **the contract**: §5 blocks verbatim, §5.1 protection rationale,
   §5.9 limits table, §5.10 Makefile targets
2. `tasks/repo-hardening-e35-backlog.md`
3. `tasks/repo-hardening-e35-implementation-sequence.md` — **your execution script**
4. `AGENTS.md` (preamble + §4 + §6 — the files you are about to amend; you must know them as-is)
5. `docs/coding-standards.md` §1 · `docs/design.md` §7.4/§11.1/§12 (the exact anchors you will insert at)
6. `README.md` (quickstart section you will extend) · `Makefile` (if present — S0 inventory decides)
7. External origin (context, not editable): akitaonrails.com — "Clean Code for AI Agents" (2026-04-20),
   "Open Source Best Practices with LLMs" (2026-05-30), "How Do I Protect Myself From My Agents Deleting
   My Stuff?" (2026-07-11), Akitando #44 (2019) and #139 (2023)

If documentation disagrees with executable configuration, stop, report the mismatch and resolve it in the
same change set.

---

## Goal

- `main` shows `"protected": true` (force-push + deletion blocked, admins included, **no required checks**
  — the §5.1 rationale is binding: required checks would reject the direct-push marathon flow);
- `scripts/backup-mirror.sh` + `make backup` replicate the repo idempotently (clone → update; never prunes;
  optional secondary remote; cron documented);
- six governance blocks installed **verbatim**: coding-standards §1.1 (agent readability: ≤ ~300-line files,
  grep-test names, provenance comments, headless tests) · AGENTS 4.6 (agent environments carry dev-only
  values) · AGENTS §6 DoD row · design §7.4 (few, deliberate relay workers — E6 inherits) · §11.1
  (build once, package many — E14 inherits) · §12 (every compose service limited);
- every compose service carries CPU/memory limits (measured first, adjusted within ±50%, headroom asserted);
- Makefile: `verify` / `build` / `up` / `down` / `backup` / `scope-check`; README quickstart gains the
  one-command path without lying about prerequisites.

The epic closes these current gaps:

- `main` accepts force-pushes and deletions from anyone with write access — one accident erases history;
- the repo has zero backup — host loss or a bad reset is unrecoverable;
- agent-readability rules exist only in an internal survey note — future epics do not inherit them;
- the single-host fleet (blue + green + Postgres + LocalStack + NGINX + simulator) has no cgroup limits —
  a canary can OOM the host;
- build-once and relay-pool principles live in nobody's memory — E14/E6 would re-derive or lose them.

---

## Locked technical decisions

1. **Numbering stays E3.5** — user-mandated interstitial; the canonical ledger is not renumbered.
2. **Protection without required checks** (spec §5.1): the identified risks are force-push and deletion;
   CI discipline is enforced by process (AGENTS §6, "red main stops work"), not by a lockout that would
   reject direct pushes.
3. **Policy blocks are byte-binding** (spec §5.3–§5.8): install verbatim; changes require sign-off.
4. **Backup contract** (spec §5.2): idempotent, never prunes, no credentials, strict exit codes.
5. **Compose limits are measured, then set** (§5.9): starting table ±50% from real `docker stats`;
   headroom Σ ≤ ~75% host RAM; fallback keys documented if the host compose is old — never silently dropped.
6. **Makefile is extend-only** (§5.10): existing targets keep name and behavior; every target (old and new)
   is executed once as proof.
7. **Zero application code**: nothing under `modules/*/src`, `apps/*/src`, `.github/`; no new dependencies.
8. **Coordination with E3** (spec §3): disjoint files; E3.5's ledger flip lands before E3's closure commit.

---

## Non-negotiable engineering rules

1. Small conventional commits (`chore(repo): …`, `docs: …`); keep `main` green at every step.
2. The structural scope check runs before every push (`modules/*/src`, `apps/*/src`, `.github` = 0 lines).
3. Executable artifacts are proven by execution: script runs twice (clone + update), every Makefile target
   runs once, `docker compose config` validates the limits.
4. Evidence is public and quotable: the protection API response, script run outputs, stats tables — all go
   into the acceptance matrix verbatim.
5. After each step: update backlog checkboxes, note deviations in the sequence file.
6. Sources 100% English; no credentials in the script, the Makefile, the docs, or the matrix.

---

## Required contracts (the E3.5 definition of shape)

- **Branch protection §5.1** — `"protected": true` with the exact profile (no check requirements).
- **Backup §5.2** — script behavior + idempotency proof + cron documentation.
- **Governance blocks §5.3–§5.8** — six verbatim insertions at named anchors.
- **Compose limits §5.9** — table applied post-measurement; validation + post-cap stats cited.
- **Makefile §5.10** — six targets; README one-command quickstart; nothing broken.

## Scope exclusions (hard boundaries)

- No changes under `modules/*/src`, `apps/*/src`, `.github/`; no new dependencies; no application code.
- No required status checks, no PR-only flow, no CODEOWNERS (solo marathon stays direct-push).
- No multi-format release pipeline, SBOM, or tag automation (E14); no relay implementation (E6 — the design
  line only); no observability tooling (E11); no agent-sandbox tooling (environment is owner-managed; only
  the §5.5 secrets rule is codified).
- No edits to the survey's internal notes; the epic docs are self-contained by design.

## Definition of Done (epic)

### Protection & backup
- [ ] `main`: `"protected": true` (public API evidence); no required checks; procedure documented
- [ ] Backup script proven idempotent; `make backup` wired; cron note in README

### Governance (the core)
- [ ] Six §5 blocks installed verbatim at their anchors; `git diff` of Step 3 shows insertions only
- [ ] Future-epic inheritance confirmed: E6 inherits §7.4, E14 inherits §11.1, every epic inherits §1.1/4.6/§6

### Resources & surface
- [ ] Every compose service limited (measured ±50%, headroom asserted); `docker compose config` green
- [ ] Makefile six targets proven (old targets unbroken); README one-command path; honest prerequisites

### Discipline & closure
- [ ] Zero diffs under `modules/*/src`, `apps/*/src`, `.github/`; `make verify` green; CI green on `main`
- [ ] `tasks/e35-acceptance-matrix.md` zero pending; ledger E3.5 ✅; CHANGELOG; lessons entry if the
      protection/required-checks interaction (or any non-obvious finding) is worth recording
