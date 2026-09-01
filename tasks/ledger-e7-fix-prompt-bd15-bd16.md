# Execution Prompt — E7 Post-Closure Fix Block: BD-15 + BD-16 (register-driven)

**Issued:** 2026-09-01 · **Executor:** the AI Software Engineer · **Auditor:** the governance side (this
handoff will be API-audited: messages vs diffs, sources read line-level, run pairs re-verified).
**Nature:** remediation of two defects found by the post-closure source deep-pass — both registered in the
E3R register (BD-15, BD-16). E7 stays flipped ✅; this block amends its matrix honestly and adds evidence.
No re-flip, no new epic. **Push is the owner's action.**

---

## Where you are starting from (verified facts — cite pairs, never memory)

- main = `6897d1d` ("record TD-14/TD-13 residual run pairs #68/#69/#70"), run **#71 `33537318509`** green.
- **BD-15 (HIGH — money-loss window, BD-11's class):** in `EventIngestionUseCase.processMessage`, the dedupe
  insert commits OUTSIDE the posting tx, and the duplicate branch (`insertEventIfAbsent == false`) ack-skips
  **regardless of stored status**. A failure between the RECEIVED insert and the `postJournal` commit freezes
  the row in RECEIVED; the SQS redelivery is then acked without posting — the money event is silently lost
  and the proof stays green. The e7 matrix race row "Consumer crash between receive and ack" documents the
  wrong recovery ("re-delivered → dedupe skip").
- **BD-16 (MEDIUM — hygiene, lesson #13):** `EventEnvelopeReader` imports **Jackson 2** (`com.fasterxml.*`)
  in ledger production sources while its own javadoc says "Strict Jackson 3 reader"; a second
  `new ObjectMapper()` is created per `extractPaymentPayload` call; and `Instant.parse`'s
  `DateTimeParseException` is NOT an `IllegalArgumentException`, so a malformed `occurredAt` escapes the
  reader's catch clause by type (it reaches the poison path only by accident, not by contract).
- Both rows carry the Fix contracts below; this prompt is their executable form.

## Sources of truth — binding

1. The E3R register rows **BD-15 / BD-16** (`tasks/create-webhook-remediation-e3r-spec.md` §2) — the fix
   contracts.
2. `tasks/ledger-core-e7-spec.md` §5.3/§5.7 (posting rules, guarantee statement) + §10 (post-commissioning
   adjudications — the as-built intake is ratified; you are hardening IT, not redesigning).
3. `AGENTS.md` §3.3/§3.4 (idempotency, outbox/event semantics), §9d (divergence = stop-and-report).

## Fix 1 — BD-15: resume-on-RECEIVED (`fix(ledger): resume posting on duplicate RECEIVED (BD-15)`)

Code contract (keep it small; no schema change — if you believe you need one, stop-and-report):

1. On the duplicate branch (`insertEventIfAbsent == false`): **re-read the stored status** and branch:
   - `POSTED` / `IGNORED` / `REJECTED` → ack + skip (as today);
   - `RECEIVED` → **resume**: in ONE transaction, claim the row first — `UPDATE ledger.events SET
     status='POSTED', note='Posted successfully' WHERE event_id = :id AND status = 'RECEIVED'` — and only if
     that conditional update affected 1 row, write journal + postings + balances and ack. 0 rows affected
     (a concurrent consumer won the resume) → ack + skip, zero writes.
   - Belt-and-suspenders: `journal_entries.event_id` is UNIQUE — if the journal insert still collides
     (races the DB guards don't cover), catch, re-read status, ack as already-posted. Never double-post;
     never ack unposted.
2. **The guard IT (BD-11's medicine — the test that fails under pass-through/lossy wiring):**
   test-local trigger raising on `ledger.journal_entries` INSERT (DDL in the Testcontainer = test
   infrastructure, never Flyway) → deliver a valid `payment.confirmed` → consumer nacks, row stays
   **RECEIVED**, zero journal rows, payment unaffected → drop trigger → **redeliver the same message** →
   ack, row `POSTED`, **exactly one** journal entry + 3 postings, balances incremented exactly once,
   `GET /v1/ledger/proof` ok. Name it like the E3R guard:
   `redelivery_after_posting_failure_resumes_and_posts_exactly_once`.
3. Unit tests: duplicate-branch matrix by stored status (RECEIVED resumes / POSTED ack-skips / IGNORED,
   REJECTED ack-skip) against fakes; the lost-resume-race branch (conditional update → 0 rows).

## Fix 2 — BD-16: Jackson 3 reader (`refactor(ledger): Jackson 3 reader and normalized parse failures (BD-16)`)

1. Switch `EventEnvelopeReader` to the house Jackson 3 line (`tools.jackson.*`). Prefer the shared JSON
   serialization support if it already exposes a Jackson 3 mapper (AGENTS §2.1 allows JSON support in
   shared); otherwise add `tools.jackson.core:jackson-databind` (same 3.1.5 as payments) to
   `modules/ledger/pom.xml` and **disclose the pom addition in the commit message**.
2. ONE field mapper reused by both methods — no per-call `new ObjectMapper()`.
3. Normalize the boundary: any parse failure inside `read()`/`extractPaymentPayload()` — including the
   timestamp parse — leaves the method as `IllegalArgumentException` (the poison contract). A unit test
   proves `occurredAt: "not-a-date"` → poison (not an accident of exception typing).
4. The javadoc's "Strict Jackson 3" becomes true. Comment = code.

## Matrix & hygiene amendments (same changesets as the fixes they describe)

- **e7 matrix:** rewrite the wrong race-table row ("Consumer crash between receive and ack") to the truthful
  mechanism, add rows BD-15 → guard IT + run pair, BD-16 → the hygiene grep + run pair. The S0 row stays as
  corrected by `8e775cb` (do not touch settled history).
- **Hygiene grep (new, permanent):** `grep -rn "com.fasterxml" modules/ledger/src/main` = **0 hits**, pasted
  with the commit id it ran at. (E7's greps missed this class; the grep list grows here.)
- Docs: no README/CHANGELOG/ledger edits needed — this is register-driven remediation, evidenced in the
  matrix. (If you disagree, stop-and-report; don't edit silently.)

## Order & discipline

1. Commit `fix(ledger): resume posting on duplicate RECEIVED (BD-15)` — code + guard IT + unit tests +
   matrix race-row correction + BD-15 matrix row.
2. Commit `refactor(ledger): Jackson 3 reader and normalized parse failures (BD-16)` — reader switch +
   normalization + unit proof + grep evidence + BD-16 matrix row.
3. Every push green; run pairs cited number AND id against the canonical tables. If the owner batches the
   push, one green run covers both commits, cited for both.

## Non-negotiable rules & stop conditions

- Scope: `modules/ledger` (+ its pom for BD-16 only), `apps/api` tests if strictly needed, `tasks/` matrix.
  Zero lines in `modules/payments`, `modules/notifications`, `apps/psp-simulator`; zero migrations; zero new
  env names; zero new dependencies beyond the disclosed `tools.jackson` (if shared doesn't provide it).
- Stop-and-report on: any felt need for a migration or schema tweak; any test that can only pass by
  weakening an existing assert; any red you cannot explain in writing.
- Commit message = diff (pre-push hunk check); the matrix row and the commit that lands it must tell the
  same story (the TD-14 lesson — evidence never cites work that didn't happen at its cited commit).

## Handoff report (API-audited)

- Both commit shas + messages; test names + run pairs (guard IT, unit branches, poison-typing proof).
- The guard IT's failure-injection mechanics quoted from source; the resume SQL quoted; the grep output with
  its commit id.
- Anything NOT done from this prompt, with reason; clarifications asked BEFORE diverging.

Then stop. On verified evidence this block closes, BD-15/BD-16 zero out in the register, and **E10
(notifications consumer — closing M2) is commissioned**.

---

## Clarifications — adjudicated before execution (2026-09-01; mechanics only, auditor-final)

1. **Guard IT trigger mechanics (Q1):** mirror the E3R guard IT (`atomicity_failure_injection_outbox_failure_rolls_back_and_recovery_works`)
   verbatim, adapted to `ledger.journal_entries` — **inline DDL inside the test method** via
   `jdbc.sql(...)`: `CREATE OR REPLACE FUNCTION fail_journal_insert() RETURNS trigger AS $$ BEGIN RAISE
   EXCEPTION 'simulated journal failure'; END; $$ LANGUAGE plpgsql;` + `DROP TRIGGER IF EXISTS` +
   `CREATE TRIGGER` BEFORE INSERT. NOT a `@TestConfiguration` (that applies class-wide and would poison
   other tests; the Flyway bean you already have there is orthogonal — it applies migrations, keep it).
   The trigger must exist only for the first leg; it is dropped mid-test before the redelivery leg.
2. **Cleanup (Q2):** both — the mid-test `DROP TRIGGER` is part of the scenario (the redeliver leg needs it
   gone), and an `@AfterEach` `DROP TRIGGER IF EXISTS trg_fail_journal_insert` is the safety net for
   assertion failures between create and mid-test drop (the container is reused; a leftover trigger would
   sabotage sibling tests).
3. **Redelivery semantics (Q3):** intended semantics = **direct second `processMessage(raw)` call** — the
   BD-15 defect lives in the use case's duplicate branch, not in wiring (BD-11 was a wiring defect; that's
   why E3R's guard rode the full HTTP path). Use-case altitude is sufficient and deterministic (zero
   LocalStack timing, zero sleeps). Condition: add one small unit test pinning the consumer's translation
   contract (`processMessage == false` → no ack) so the thin layer stays thin and honest.
4. **Reader test construction (Q4):** switch existing `EventEnvelopeReaderTest` constructions to the
   **default constructor** — tests must exercise what production runs (the default mapper config is part of
   the tested surface, including Java-time handling). Keep the injecting constructor for edge tests that
   genuinely need a custom mapper, not as default isolation ritual. The new poison-typing proof
   (`occurredAt: "not-a-date"` → `IllegalArgumentException`) uses the default constructor too.
5. **Matrix rows (Q5):** yes — same columns as the e7 matrix's Register Traceability
   (Item | Deliverable | Test/Evidence | CI Run | Status); add a "Post-closure remediation" subsection with
   BD-15 and BD-16 rows. Do NOT import the E3R register's column format into the e7 matrix — each artifact
   stays internally consistent so pairs stay machine-checkable.
