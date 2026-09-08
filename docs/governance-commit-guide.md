# Governance commit guide — owner runbook (2026-09-04, post M3 ✅)

## 1. What to commit (workspace → repo, one commit)

The repo is missing the ENTIRE governance corpus produced in this channel. Files to land under `tasks/`
(owner copies from the workspace):

- `tasks/e3r-create-webhook-remediation/e3r-block1-verification.md` — the register: E5+E8 closures & audits, Q7–Q11 addenda,
  TD-22→TD-30 (incl. TD-29 author-side spec defect + TD-30 evidence-violations record), DEBT-7 mint,
  3 external-analysis triages, E9 rounds 1–6 + **E9 CLOSED + M3 ✅ declaration**
- `tasks/e9-delivery-hardening/delivery-hardening-e9-prompt.md` / `-backlog.md` / `-sequence.md` / `-spec.md` /
  `-execution-prompt-block1.md` — E9 package 5/5 WITH the Q11 addendum + TD-26/DEBT-7 riders +
  §6.4.1 emenda (the versions the channel adjudicated — her committed copies predate some of it)
- ~~`tasks/handoff-dod.md`~~ — **RESOLVED (reorg 2026-09-08):** the Handoff DoD landed as the ONE canonical
  `docs/handoff-dod.md` (via `06e953b`, updated `7a416a3`); the byte-identical `tasks/` duplicate was deleted (chore/tasks-per-epic-dirs).
- `tasks/e3r-create-webhook-remediation/create-webhook-remediation-e3r-spec.md` — E3R register annotations (TD-21..25 era)

**Commit message (ready):**
```
docs(governance): land channel register, E9 package (with riders), handoff DoD

- E9 CLOSED + M3 ✅ declared (register round 6): chain d34c414..06e953b, content run
  #155 33921797910 (head df06c9c), tip 06e953b green (#157 33923707334)
- TD-22..TD-30 recorded; TD-29 (author-side spec defect, E1-E4 emenda) and TD-30
  (evidence violations) carry full audits
- E9 package synced to channel-adjudicated versions (Q11 addendum, §6.4.1, S6 riders)
- docs/epics.md E9 row: counts normalized to anchored inventory (23/23 executable @Test — TD-31
  correction; sum corrected 22→23 per TD-34)
```

## 2. epics.md E9 row — paste-ready replacement (counts normalized per audit grep)

**TD-31 CORRECTION (2026-09-05):** my original numbers here (2/8/4/12/5/3 = 34) were WRONG — an
unanchored `grep -c "@Test"` also counts `@Testcontainers`. Anchored truth (`grep -n "@Test$"`):
OutboxExhaustionIT 1 · OutboxRequeueIT 6 · OutboxAdminRotationIT 2 · OutboxRepublishIT 10 ·
OutboxRepublishRotationIT 3 · Scenario20NoDoubleJournalIT 1 = **23 executable methods** (sum
1+6+2+10+3+1; TD-34 correction — the original TD-31 note wrote 22 from the same list, an
off-by-one that the E14 adjudication 2026-09-07 corrected; per-handoff-dod §4 row) — and the
engineer's per-class counts (1/6/2/10/3) were CORRECT all along (register retracts the charge; only
her TOTALS carried off-by-one arithmetic and the run ids were fabricated). Replace the row with:

```
| E9 | Delivery hardening: backoff, EXHAUSTED, requeue, republish | payments, api | E6, E7 | M3 | ✅ 2026-09-04 — content run #155 `33921797910` (head `df06c9c`), citation run #158 `33924020657` (head `b9d3f2c`), tip `06e953b` green #157 `33923707334` — S1 exhaustion `OutboxExhaustionIT` 1/1 (`d34c414`); S2 audited requeue sc.19 `OutboxRequeueIT` 6/6 + Q11 rotation 403 `OutboxAdminRotationIT` 2/2 (`8fcb2e1`); S3 republish, deterministic UUID identity `OutboxRepublishIT` 10/10 + `OutboxRepublishRotationIT` 3/3 (`eb7c06d`→`df06c9c`); S4 no-double-journal: guard `77ac744` + deterministic harness `Scenario20NoDoubleJournalIT` 1/1 (`df06c9c`, no @Disabled); S5 `docs/runbooks/dlq-inspection.md` (`21f0e45`); S6 docs + flips + citation — total 23/23 executable green (anchored counts, TD-31; sum corrected TD-34) — matrix evidenced (`tasks/e9-delivery-hardening/delivery-hardening-e9-spec.md` §10) — **M3 ✅** (chain E5+E8+E9; register `tasks/e3r-create-webhook-remediation/e3r-block1-verification.md`) |
```

(This edit rides the SAME governance commit — docs-only, no new citation needed: the citation
semantics were satisfied at `b9d3f2c`/`06e953b`; this is count normalization, not a claim change.)

## 3. Kickoff message for the engineer (paste & send)

> **M3 ✅ declared — E9 closed with the full chain audited (content run #155 `33921797910`, tip
> `06e953b`). The engineering in `df06c9c` is approved in full: deterministic UUID identity, guard,
> deterministic harness, honest runbook.**
>
> Two items carry into the M4 kickoff:
> 1. **TD-30 acknowledgment** (register): the evidence violations in handoffs 2–4 (the "@Disabled per
>    owner" attribution, counts and run refs not matching trees) are resolved-as-recorded; an explicit
>    acknowledgment is expected in your first M4 handoff.
> 2. **Rule zero is now in-repo** (`docs/handoff-dod.md`): every number in a handoff is pasted from a
>    command output included in the handoff. Counts come from surefire or are omitted. Owners are
>    quoted, never paraphrased.
>
> The M4 package is being prepared in the owner channel. **No new work until it lands** — same protocol
> as E9: package → Block 1 → audit → Block 2 → flips → citation.

## 4. Then: M4 (Finish) — sequence per roadmap + maturity carry

- **E11 — Observability first** (correlation + JSON logs BEFORE metrics; rider TD-22; on-call drill IT:
  "pending txid findable in ≤2 min" in CI; log-scrubbing assertion rider N4)
- **E12 — Deploy** (blue-green canary scripts, runtime smoke, shutdown-under-load)
- **E13 — Quality & security gates** (OWASP + JaCoCo together, floors 70/75/80/50/40; Trivy/SBOM;
  threat model addendum; **evidence-lint rider**: validate cited run ids/counts in epics/matrix via
  `gh api` in CI; **DEBT-7** consolidation in the refactor window; PMD/Checkstyle adjudication due here)
- **E14 — Release** (semver tags, GHCR, SBOM of shipped image, restore drill → **v1.0.0**) +
  maturity re-assessment protocol (registered predictions get tested)
