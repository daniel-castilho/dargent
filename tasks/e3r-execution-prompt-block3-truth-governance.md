# Execution Prompt — E3R Block 3: Truth & Governance — v2 (post-audit of `f11cd2c`)

**Issued:** 2026-08-30 · **Executor:** the AI Software Engineer closing the E3R epic
**Supersedes:** v1 of this prompt. Step 0 partially landed in `f11cd2c` (run #26 `33326648770`, green) and was
line-level audited against the sources — not the message. This version re-scopes the remainder; items marked
**DONE** are landed, do not redo them.
**Operating principle (unchanged):** a green CI proves that tests pass — not that they are right, and not that the
code exists. Evidence is a test that runs in CI, cited by name + run id.

---

## Where you are starting from (verified facts — cite run ids, never memory)

- `main` is at `f11cd2c` (parent `ffc596c`); run #26 (`33326648770`) green; before it #24 (`33318535724`) and
  #25 (`33321575303`); Block 1 evidence: #17 (baseline), #18 (`33282800600`, designed red), #19 (`33285295818`).
- **DONE in `f11cd2c`** (verified in source): Jackson 3 `ObjectMapper` (`tools.jackson…JsonMapper`) bean in
  `PaymentsCompositionConfig` + injection into `WebhookIntakeUseCase`; strict parse — missing
  type/txid/endToEndId/amount/paidAt → IAE → row `IGNORED` with reason `parse error: …`; audit sentinel constant
  `WEBHOOK_AUDIT_ACTOR = 00000000-0000-0000-0000-000000000000` replacing the random actor; this spec's register
  committed to the repo.
- **NOT done despite the message claiming it (TD-11 — third message≠diff instance):** `Instant.parse(paidAt)` is
  still bare (outside the strict-parse try, step 5) — a signature-valid payload with a malformed `paidAt` still
  poisons the row in `RECEIVED` forever; and the "BD-11 guard" the message cites is the pre-existing happy-path
  IT, which cannot distinguish a real `TransactionTemplate` from a pass-through executor (atomicity only
  manifests on failure). The failure-injection IT is still missing.
- V107 is permanently vacant (S0 decision); the next migration, if one is ever needed, is V109. BD-14's
  adjudication below needs **no migration**.

## Owner adjudications you must execute (decided 2026-08-30 — quoted, not negotiable)

1. **BD-14 — sentinel RATIFIED:** the zero-UUID sentinel is the documented system actor for PSP-callback audit
   rows (`WEBHOOK_AUDIT_ACTOR = 00000000-0000-0000-0000-000000000000`); V106's `actor_key_id uuid NOT NULL`
   stands as intentional; E4 spec §5.3 step 7 is amended accordingly (sentinel replaces the earlier `null`
   mandate). No migration. The unilateral in-block resolution is thereby retroactively accepted — but the rule
   it triggered is binding on all future cases: R8 amendment (d).
2. **DEV-R2-4 stands "accepted with conditions"** (A0 golden assertions landed 7/7 in Block 2; R7 documents TD-1
   honestly). Formal owner ratification is requested in the handoff, not granted by default.

## Sources of truth — binding, in this order

1. `tasks/create-webhook-remediation-e3r-spec.md` — §2 register (BD-1…BD-14, MS-1…MS-3, TD-1…TD-11) is the
   closure checklist; §5.6 the ledger row texts; §5.7 the governance texts (this block amends/extends them).
2. `tasks/e3r-block1-verification.md` — the audit trail: commit chains, run tables, adjudications (DEV-R2-4,
   DEV-R6), A0 tables. Copy evidence FROM it, never from memory.
3. `tasks/create-webhook-remediation-e3r-backlog.md` / `-implementation-sequence.md` (R7/R8 steps).
4. `AGENTS.md` (you will amend), `docs/lessons.md` (you will write #14), `docs/epics.md` (you will flip), and
   `tasks/webhook-intake-e4-spec.md` (you will amend §5.3 step 7 per BD-14).

## Step 0 remainder — the only code in this block (tests first where applicable)

1. **BD-13 residual — guard `paidAt`:** move `Instant.parse(payload.paidAt())` inside the strict-parse block;
   any `DateTimeParseException`/IAE → row `IGNORED` (reason `parse error: paidAt …`) + `200 ignored` — never a
   throw after signature OK. Poison IT: signature-valid payload, every field valid except
   `paidAt: "not-a-date"` → row `IGNORED`, ack `200`, `payments` table untouched. (This is the IT v1 ordered;
   it did not land.)
2. **BD-11 — the real guard (failure-injection IT):** test-local trigger raising on `payments.outbox` INSERT
   (DDL inside the Testcontainer = test infrastructure, never a Flyway migration) → send valid webhook → `500`,
   row stays `RECEIVED`, payment **NOT** confirmed, zero outbox rows for it → drop trigger → redeliver the same
   payload → `PROCESSED`, payment confirmed, exactly one outbox row, audit row present.
   Design property that makes it a guard: with a pass-through executor the payment update is already committed
   when the trigger fires, so the "payment NOT confirmed" assert goes red. Prove it locally once (swap the
   executor to a pass-through, watch the IT fail, revert) — the green CI run is the evidence; the local red is
   your method check, described in the handoff.
3. **BD-14 — write the ratification down (same changeset as the code it describes):**
   a. Code: javadoc on `WEBHOOK_AUDIT_ACTOR` — "ratified system actor for PSP callbacks (owner decision
      2026-08-30, E3R BD-14); zero UUID is intentional and greppable".
   b. E4 spec §5.3 step 7: replace the `null` mandate with the sentinel + one-line rationale (PSP callbacks have
      no API-key actor; V106's NOT NULL stands; a deterministic system actor keeps forensic queries simple).
   c. IT assert (extend the happy-path IT or the redeliver leg of item 2): exactly one audit row for the event
      with `actor_key_id = WEBHOOK_AUDIT_ACTOR` and the confirm action — column/action names read from V106 and
      the use case, never guessed.
   Suggested commits (conventional; message = diff, re-verified per the rule below):
   `fix(payments): guard webhook paidAt parsing (BD-13 residual)` ·
   `test(payments): atomicity failure-injection guard + audit-actor assert (BD-11, BD-14)` ·
   `docs(e4): ratify sentinel audit actor for PSP callbacks (BD-14)`.
   Every push green; run ids recorded for the matrix.

## Message hygiene — standing rule born from TD-11

Before **every** handoff: `git log -1 --format=%B`, then re-read the actual diff (`git show`) and verify every
bullet is carried by a real hunk. A claim the diff does not carry = fix the message or the code **before**
handoff. Local prints are never evidence — the CI decides. R8 lands this as amendment (e).

## R7 — documentation truth (no poetry)

- [ ] **Backlog:** R0–R6 ☑ with evidence pointers (runs #14–#26 + Step 0 remainder's runs); R7/R8 stay ☐ until
      this block closes them.
- [ ] **`tasks/e3r-acceptance-matrix.md` rebuilt cell-by-cell from spec §2** (the committed placeholder is TD-9:
      misquoted register + rejected-paraphrase structure — replace, don't patch): every BD/MS/TD id → fix
      commit → test name → run id. Cite #18 (designed red), #19, #22–#26, plus the Step 0 remainder's runs.
      BD-14's cell cites the ratification + the audit-row IT; TD-11's cell cites the guarded code + poison IT —
      and states plainly that `f11cd2c`'s message claimed the guard before it existed.
- [ ] **`tasks/e3-acceptance-matrix.md` rewritten** (prior non-CI evidence voided; superseded-by-E3R noted; TD-1
      documented honestly per DEV-R2-4: the original scenario IT never compiled, was deleted, replacement =
      `CreatePaymentIT` + the A0 golden-assertions audit).
- [ ] **`tasks/e4-acceptance-matrix.md` rebuilt from scratch** (the `97882494` fabrication was banner-voided):
      validator vectors, scenarios 6–10, ignored×3, full loop, atomicity guard — with run ids.
- [ ] **TD-6 decision:** commit real `e1`/`e2` matrix files or correct the artifact-index rows citing them.
- [ ] **README one voice:** create + webhook live **via E3R (runs #19, #25, #26…)** — not "(E3)", not "lands
      with E4"; M1 ◐ until the closing changeset (then ✅ with the final run id); the Testing section's
      "reconciliation scenario runs in CI" lie removed (E5 does not exist); money-flow diagram marked as target
      state; guarantees table keeps its forward-looking hedge.
- [ ] **CHANGELOG truth-scoped (TD-7):** the E3R bullet claims only what cited runs prove, with run ids —
      present tense only for evidence that exists.
- [ ] **design §8.2 + E4 spec §3.1 sync (DEV-R6):** inbound HTTP layer lives in `apps/api`;
      `adapter/in/webhook/` never existed as-built; adjudication noted. (§5.3 was amended in Step 0 — keep the
      three sync targets consistent with each other.)
- [ ] **Push the E3R workspace docs** (spec with BD-14/TD-11 rows, verification addenda, block prompts) as part
      of the docs commits — R7 integrates, never invents.
- [ ] **Hygiene greps re-run at the final commit**, outputs pasted with that commit id (TD-10 rule): no
      `String.format` JSON in prod; no hardcoded callback/merchant; no `Instant.now()` in request paths; no
      `com.fasterxml.jackson` in prod; no `*.disabled`/`*Debug*` under test trees. Read the sources alongside
      the grep — a satisfied grep is not a read tree (F2 rule).

## R8 — governance & ledger (exact texts in spec §5.7, extended by this epic's adjudications)

- [ ] **AGENTS.md:** §5.5 (disabled test = registered debt; re-enable → fix code, never expectations), §5.6
      (matrix evidence = CI test name + run id), §7 (commit message describes exactly its diff), DEBT-3 row —
      **plus the five amendments this epic earned:**
      (a) a spec-test that cannot compile is a stop-and-report defect; replacement/deletion requires owner
      sign-off (DEV-R2-4);
      (b) inbound HTTP adapters live in the boot app; AGENTS §2.2 language reconciled with the as-built
      convention (DEV-R6);
      (c) any pasted grep/verification output cites the commit id it ran at; "done" = pushed + green run id
      (TD-10);
      (d) **a schema↔spec divergence (migration vs contract) is a stop-and-report owner decision — never
      resolved in-block, however defensible the improvisation** (born BD-14);
      (e) **pre-push message self-check: every message bullet re-verified against the diff** (born TD-11 —
      three instances: TD-5, TD-10, TD-11).
- [ ] **`docs/lessons.md` #14:** green CI ≠ right tests ≠ code exists; the first act of remediation is enabling
      the disabled specification and watching it fail; audit beats attestation.
- [ ] **Ledger flips in the closing changeset — and only with the register zeroed:** E3 ✅, E4 ✅, E3R ✅, each
      citing its run ids, per the §5.6 texts as updated by this block. Zeroed means: BD-13 residual closed
      (poison IT green), BD-11 guard landed, BD-14 closed by ratification record + IT assert, TD-11 closed by
      the same evidence + honest matrix entry, and every other §2 id already carrying test + run id. Raw-verify
      `docs/epics.md` after push.
- [ ] Final: `mvn -B verify` green locally; final CI run green on `main` (id cited); scope diff = 0; **E6 is
      commissioned next** (outbox + messaging — BoE sizing + outbox retention/purge from the E6 seed), E5
      follows sequentially.

## Non-negotiable rules

1. **Step 0 remainder is the only code.** If R7/R8 work exposes a defect beyond it — stop, report, register.
   BD-14 is the cautionary tale: an in-block improvisation, even a good one, is a governance miss.
2. **You now own documentation — and the docs own the truth:** every claim carries a run id; every status
   matches the tree; nothing celebrates ahead of evidence (TD-7 is the pattern).
3. Commit messages describe exactly their diffs; conventional commits; docs commits batch only within R7 or R8.
4. Evidence citations are run ids, API-verifiable — the closing report will be audited exactly like Blocks 1–2
   (messages checked against diffs, sources read line-level, never prints accepted).
5. Scope: `modules/payments` (Step 0 only), `apps/api` (tests only), `tasks/`, `docs/`, `README.md`,
   `CHANGELOG.md`, `AGENTS.md`. Zero lines in `apps/psp-simulator`, `modules/ledger`, `modules/notifications`.

## Stop conditions

| When | Do |
|---|---|
| Schema contradicts a spec contract (any nullability/default vs any §) | **Stop-and-report** — owner decision (BD-14 rule). Never improvise around it |
| A message bullet cannot be matched to a hunk | Fix the message or the code before handoff — never push the claim |
| A matrix cell cannot cite a real test + run id | The cell stays open and says so — never invent evidence |
| Ledger flip feels premature (any register item open) | It is premature. Close the item first |
| Docs contradict each other mid-pass | Resolve in the same changeset; a half-truthful repo is the defect |
| CI red on any push | Artifact, classify in writing, fix — never push on an unexplained red |

## Handoff report (will be API-audited)

- Step 0 remainder run ids (each push green); BD-13 residual / BD-11 guard / BD-14 assert → test names → run ids.
- The local red proof of the guard (executor swapped to pass-through → IT red → reverted), described; the green
  CI run id is the actual evidence.
- R7: matrix files + the exact run ids cited per section; README/CHANGELOG diffs summarized; TD-6 resolution.
- R8: AGENTS sections + amendments (a)–(e) landed; lesson #14; ledger row texts as committed; final run id.
- Hygiene grep outputs **with the commit id they were run at** — and confirmation the sources were read, not
  just grepped.

Then stop. On verified evidence, E3R closes, the ledger flips are ratified, and **E6 is commissioned** (outbox +
messaging — with the BoE sizing and outbox-retention requirements from the E6 seed), E5 following sequentially.
