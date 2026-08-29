# Repo Hardening E3.5 — Technical Specification

## Epic E3.5 — Agent Engineering & Repository Hardening (the Akita pass)

**Priority:** P1 (small, high leverage — runs alongside E3/E4; zero code conflicts)
**Companions:** `repo-hardening-e35-backlog.md` · `repo-hardening-e35-implementation-sequence.md` · `ai-software-engineer-prompt-repo-hardening-e35.md`
**Baseline:** E0+E1+E2 closed. Origin: external knowledge survey (Fabio Akita / akitaonrails.com, 2026-08-29)
that validated our AI-engineering method with field data and exposed 4 concrete gaps. This epic CLOSES the gaps
by (a) changing repo settings/scripts/compose where the gap is operational and (b) — primarily — **writing the
new rules into the governing documents** (coding-standards, AGENTS.md, design.md) so they bind every future
epic automatically. The engineer does not author policy here; she **installs** it: §5 of this spec carries the
verbatim blocks to insert.

---

## 1. Purpose

The Dargent method (AI engineer + explicit rules + TDD + CI-as-safety-net) was independently validated by
Fabio Akita's field experience (5 months of agent marathons, ~40 repos, 500k LOC; "TDD became a technical
obligation"; CI caught 50+ real bugs). The same survey exposed our gaps: an unprotected `main`, no backup,
no agent-readability conventions written down, a single-host compose fleet with no resource limits, and
release/pool-sizing principles that existed only implicitly. This epic makes the method survivable:
**protection, backup, codified conventions, bounded resources, explicit build-once rules.**

## 2. Scope

### In scope
- Branch protection on `main` (force-push/deletion block — §5.1) + public-API evidence;
- Backup: mirror script + Makefile target + documented schedule (§5.2);
- Governance inserts: agent-readability conventions, provenance-comment rule, agent-environment secrets
  rule, build-once-packaging-many, compose-limits principle, relay pool-sizing rationale (§5.3–§5.8 —
  verbatim blocks);
- Compose resource limits for every service, measured then set (§5.9);
- Makefile standardization (`verify`, `build`, `up`, `down`, `backup`, `scope-check`) + README quickstart
  one-command path (§5.10);
- Ledger/CHANGELOG/matrix closure.

### Out of scope
- **Any change under `modules/*/src` or `apps/*/src`** — zero application code (the scope check is
  structural, not behavioral);
- CI workflow yaml changes (branch protection is a repo **setting**, not a file);
- Required status checks on `main` (would break the direct-push marathon flow — §5.1 rationale);
- Multi-format release pipeline, SBOM, tag automation (E14); relay implementation (E6 — only the design
  line lands here); monitoring/observability tooling (E11); ai-jail-style sandboxing (engine environment
  is owner-managed; only the secrets rule is codified).

## 3. Architectural constraints

- Numbering stays **E3.5** (user-mandated interstitial; canonical ledger is NOT renumbered).
- Coordination with E3 (in flight): E3 owns design §5.1/§6.3 + README callout + `.env.example`; E3.5 owns
  coding-standards §1, AGENTS §4/§6, design §7.4/§11.1/§12, README quickstart, Makefile, scripts/, docker/.
  **File overlap: none** — except ledger + CHANGELOG at closure. Close E3.5 **before** E3's closure commit,
  or rebase the ledger flip.
- Compose syntax: `deploy.resources.limits` (compose v2 canonical). If the host's compose version rejects
  it, fall back to the documented compatible key set (§5.9 note) — never silently drop a limit.
- The backup script **never prunes** the destination, never embeds credentials, and is idempotent
  (re-run = update, not re-clone).
- New deps: **none**. New prod code: **none**.

## 4. Decision map (traceability)

| Spec element | Source |
|---|---|
| TDD-as-obligation + CI safety net validated (50+ real bugs caught) | akitaonrails.com "Clean Code for AI Agents" (2026-04-20) + "Zero to Post-Production in 1 Week" (2026-02-20) |
| Agent readability: small files, grep-test names, provenance comments, headless tests | "Clean Code for AI Agents" (2026-04-20) |
| 3 pillars: install surface / tests+CI / docs; standardization ⇒ trustworthy automation | "Open Source Best Practices with LLMs" (2026-05-30) |
| Build once, repackage many (artifact reuse, sha) | "Open Source Best Practices with LLMs" (2026-05-30) |
| Distrust → engineering: snapshots, backups, secrets out of agent reach | "How Do I Protect Myself From My Agents Deleting My Stuff?" (2026-07-11) |
| Threads cost ≥ 1 MB stack + context switch ⇒ few, deliberate workers | Akitando #44 (2019-03-20) |
| Containers = cgroups + namespaces; single host needs explicit limits | Akitando #139 (2023-03-02) |
| Protection without required checks (direct-push flow preserved) | This spec §5.1 rationale (GitHub rejects direct pushes to branches with required checks) |

## 5. Exact contracts

### 5.1 Branch protection on `main` (repo setting — owner action or `gh api` with admin token)

Enabled: **block force pushes** · **block deletions**. Explicitly NOT enabled: required status checks
(GitHub rejects *direct pushes* to branches with required checks — this repo's marathon flow pushes
directly; CI discipline is enforced by AGENTS §6 DoD and the sequence rule "a red `main` stops work",
not by lockout). Include administrators: yes (the point is protecting humans from themselves).

**Evidence:** `GET /repos/daniel-castilho/dargent/branches/main` → `"protected": true` (public field —
cite the response in the matrix). Procedure for the owner (UI path) documented in the epic's PR-free
runbook note: Settings → Branches → Add rule → `main` → block force pushes + deletions.

### 5.2 Backup — `scripts/backup-mirror.sh`

Behavior: `DARGENT_BACKUP_DIR` (default `../dargent-backup.git`); first run `git clone --mirror`, later
runs `git remote update` on the existing mirror (idempotent); optional `DARGENT_BACKUP_REMOTE` → after
updating the mirror, `git push --mirror` to it; **never deletes** anything at the destination; nonzero
exit + stderr on any failure; no credentials in the script or its output.

Evidence: script run twice into a temp dir (clone → update), `git rev-parse HEAD` in mirror == pushed
head, second run logs "updated, not re-cloned". Makefile target `backup` calls it. README (ops section)
documents a cron example (`0 4 * * *`).

### 5.3 coding-standards.md — insert after §1 (verbatim; becomes §1.1)

```markdown
### 1.1 Agent readability (the code's primary reader is often an agent)

- **File size ≤ ~300 lines** (think hard at 500): an agent reads a whole file in one tool call and reasons
  with full attention; pagination fragments its mental model. Split by responsibility, not by magic number.
- **Names pass the grep test**: grepping a name returns the relevant hits and little else. Generic names
  (`Manager`, `Handler`, `process`, `data`) fail the test and the review.
- **Provenance comments are first-class context**: the why (the bug that motivated the code, the upstream
  issue, the commit) is never pruned in review. Obvious "what" comments remain a defect (§1).
- **Tests run headless**: the command is documented (`make verify`), output is parseable, no manual seeds
  or credentials. An agent that cannot run the tests goes blind.
```

### 5.4 AGENTS.md — §6 DoD checklist gains one row (verbatim)

```markdown
- [ ] New files pass the readability bar (≤ ~300 lines, grep-test names); provenance comments preserved
```

### 5.5 AGENTS.md — §4 gains one rule (verbatim; becomes 4.6)

```markdown
4.6. Agent environments carry dev-only values only. Real secrets never enter an agent session, a prompt,
or a fixture — documented dev defaults (`dev-only-secret`, compose defaults) are the ceiling. Backups and
branch protection are the engineering answer to agent mistakes, not afterthoughts.
```

### 5.6 design.md §11.1 — pipeline gains one bullet (verbatim)

```markdown
- **Build once, package many:** the image job consumes the jar artifact produced by the build job (never
  recompiles); SBOM and release attest the exact shipped bytes.
```

### 5.7 design.md §7.4 — outbox section gains one bullet (verbatim)

```markdown
- Relay workers are few and deliberately sized: each JVM thread costs ≥ 1 MB of stack plus context-switch
  overhead. Default N=4 with the rationale recorded in the relay epic — never thread-per-anything.
```

### 5.8 design.md §12 — runtime section gains one bullet (verbatim)

```markdown
- Every compose service declares CPU/memory limits: blue + green + Postgres + LocalStack + NGINX +
  simulator share ONE host, and cgroups are the only thing stopping a canary from OOM-ing the fleet.
```

### 5.9 Compose resource limits (measure → set → document)

Starting point (S4 measures real usage with `docker stats --no-stream` under the running fleet and adjusts
within ±50% before committing; record the measurement in the matrix):

| Service | memory limit | cpus |
|---|---|---|
| postgres | `1g` | `"1.0"` |
| localstack | `512m` | `"0.5"` |
| api-blue | `768m` | `"1.0"` |
| api-green | `768m` | `"1.0"` |
| nginx | `128m` | `"0.25"` |
| psp-simulator | `512m` | `"0.5"` |

Syntax: `deploy.resources.limits` per service. Host-headroom check: Σ limits ≤ ~75% of host RAM — assert
in the matrix with `free -h`. Evidence: `docker compose config` validates; `docker stats --no-stream`
after `up` shows the caps applied.

### 5.10 Makefile standardization + README quickstart

Targets (extend the existing file — S0 inventories; never break an existing target):

| Target | Does |
|---|---|
| `verify` | `mvn -B verify` (the agent-runnable test command; the one docs cite) |
| `build` | `mvn -B -DskipTests package` |
| `up` / `down` | compose fleet up / down |
| `backup` | `bash scripts/backup-mirror.sh` |
| `scope-check` | the epic boundary greps (modules/apps src diff + boundary script) — one command every epic's pre-push check becomes |

README quickstart gains the one-command path (`make up` → stack; `make verify` → full suite) and the
backup/cron note. Install surface stays honest (prerequisites unchanged — no fake one-liner).

## 6. Risks & troubleshooting

| Risk | Likelihood | Mitigation |
|---|---|---|
| Required checks enabled "for safety" → direct pushes blocked, marathon stalls | High if improvising | §5.1 rationale is binding: force-push/deletion block ONLY; the engineer does not enable check requirements |
| `gh api` lacks admin scope → protection change fails | Medium | Owner applies the UI path (2 minutes); engineer gathers before/after API evidence and writes the procedure down |
| Compose limits too tight → OOM-kill under canary/chaos | Medium | Measure first (S4), ±50% adjustment window, host-headroom assert; limits are configurable env overrides where compose supports |
| Old compose rejects `deploy.resources.limits` | Medium | Documented fallback key set (`mem_limit`/`cpus`) — pick per host, note in matrix; never drop silently |
| Mirror script prunes or embeds credentials | Low (contract) | §5.2 behavior is a stop condition; code review the script against it |
| Doc insert conflicts with E3's doc sync | Low | §3 coordination rule: E3.5 closure first, or rebase — file sets are disjoint |
| Makefile breaks an existing target | Low | S0 inventory; extend-only; run every pre-existing target once after the change |

## 7. Closure checklist (epic DoD)

- [ ] `main` shows `"protected": true` via public API (evidence cited in matrix)
- [ ] Backup script proven idempotent (clone → update run); `make backup` wired; cron documented
- [ ] §5.3–§5.8 verbatim blocks installed in coding-standards / AGENTS / design — text matches this spec
- [ ] Compose: every service limited; `docker compose config` green; measured usage recorded; headroom asserted
- [ ] Makefile targets per §5.10; README quickstart one-command path; no pre-existing target broken
- [ ] Zero diff under `modules/*/src`, `apps/*/src`, `.github/`
- [ ] `tasks/e35-acceptance-matrix.md` zero pending; ledger E3.5 ✅; CHANGELOG entry; lessons entry if
      anything non-obvious surfaced (candidate: the required-checks/direct-push interaction)
