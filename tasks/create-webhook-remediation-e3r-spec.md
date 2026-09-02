# Create & Webhook Remediation E3R — Technical Specification

## Epic E3R — "The Code Must Match the Docs": restore the create path and land webhook intake for real

**Priority:** P0 — the platform's public promises (README curl, money loop) are currently unsubstantiated in code
**Companions:** `create-webhook-remediation-e3r-backlog.md` · `create-webhook-remediation-e3r-implementation-sequence.md` · `ai-software-engineer-prompt-create-webhook-remediation-e3r.md`
**Baseline:** commit `47d24408`, CI run #13 (`33267438415`) green. E1/E2 stand as delivered. **E3 is reopened in
substance** (create path never landed over HTTP; use case violates its own spec). **E4 is reopened** (only V108 +
the `WebhookEventStore` port/adapter exist). Origin: the 2nd external audit (2026-08-29), every claim verified
against the code before acceptance.

> **Driving principle (new, binding):** a green CI proves that tests pass — not that they are right, and not
> that the code exists. Evidence for every claim in this epic is a test that runs in CI, cited by name + run id.

---

## 1. Purpose

The 2nd external audit established that E3's closure was documentation, not software: `PaymentController` ships
**two `@GetMapping`s and no `POST /v1/payments`**; `CreatePaymentUseCase` exists but violates the spec it claims
to implement (§5.7/§5.8 of `create-payment-e3-spec.md`) in at least ten ways; the scenario IT that proves the
contract was shipped **disabled** (33 KB of written-but-off evidence); two debug tests were committed. E4's
"~90% done" was refuted the same way: commit `47d24408` delivered `V108__webhook_events.sql` + the
`WebhookEventStore` port and JDBC adapter — **no validator, no intake use case, no `POST /webhooks/psp`**, and
the E4 document set was never committed.

This epic does not re-plan E3/E4 — their specs remain the binding behavior contracts. E3R makes the code obey
them: re-enable the disabled specification, fix the use case, land the two missing endpoints, delete the debug
artifacts, re-evidence every matrix cell with CI tests, and install the governance that makes this class of
failure impossible to repeat silently.

## 2. Defect register (audit 2026-08-29 — each item verified in code)

This register is **binding**: every item closes with a CI test (name + run id) or an honest doc correction.
"No change needed" is a valid closure only with a written justification in the matrix.

### 2.A — Behavior defects in the create path (vs `create-payment-e3-spec.md` §5.7/§5.8)

| ID | Defect (observed) | Violates | Fix |
|---|---|---|---|
| BD-1 | Transactional core is announced but not structured — no `TransactionTemplate`/transactional wiring; the §5.8 script (idempotency → payment → outbox → audit) does not run atomically | E3 §5.8; coding-standards §5 | R2 |
| BD-2 | Payment does not land canonically in `PENDING` (confirmed-on-create behavior), instead of `PENDING` after the core with `CONFIRMED` reserved to the webhook | E3 §5.1/§5.8; design §6.1 | R2 |
| BD-3 | PSP truth is discarded: `updatedPayment`/`failedPayment` results are dropped and `updateIfVersionMatches(payment, 0)` is invoked with the stale pre-PSP aggregate (and a hardcoded expected version `0`) in both branches — `expires_at` (PSP truth) and the FAILED reason never reach the DB | E3 §5.7; AGENTS §3.2 | R2 |
| BD-4 | Zero D19 retry: a single catch marks the payment failed; no `PSP_CREATE_MAX_ATTEMPTS`, no linear backoff via injected sleeper, no 409 `txid_already_exists` read-back path | E3 §5.7; D19 | R2 |
| BD-5 | `requestId=""` — the `X-Request-Id` never reaches the outbox envelope | E3 §5.4/§5.6 | R2 |
| BD-6 | Idempotency snapshot is a stub (`Map.of("txid")`) instead of status + exact 2xx response body; replay is not byte-equal | E3 §5.1.3 | R2 |
| BD-7 | `actor_key_id = UUID.randomUUID()` — the audit trail fabricates an actor instead of recording the authenticated API key's id | E3 §5.8/§5.9 | R2 |
| BD-8 | Outbox payload built via `String.format` JSON instead of the shared Jackson-3 serializer (escaping, determinism, lesson #13) | E3 §5.6; lesson #13 | R2 |
| BD-9 | PSP callback hardcoded to `https://example.com/callback` instead of `PSP_CALLBACK_URL` — the simulator's webhooks would fire into the void | E3 §3.3/§5.7; E2 contract | R2 |
| BD-10 | Controller read side: BR Code merchant hardcoded (`dargent-dev-receber@example.com` / `Dargent Dev LTDA` / `SAO PAULO`) instead of the configured PIX profile; `Instant.now()` instead of the injected `Clock`; cursor decoded for validation but the **raw string** forwarded to `findPage` instead of the decoded keyset | E3 §5.2/§5.3/§3.3; AGENTS §5.3 | R3 |

### 2.B — Missing HTTP surface

| ID | Gap (observed) | Contract | Fix |
|---|---|---|---|
| MS-1 | `POST /v1/payments` does not exist — `PaymentController` is the only controller and exposes exactly two `@GetMapping`s | E3 spec §5.1 (verbatim) | R3 |
| MS-2 | Create use case not exposed: no endpoint wiring in `apps/api` for the command path | E3 §3.1 | R3 |
| MS-3 | `POST /webhooks/psp` does not exist: no `WebhookSignatureValidator`, no `WebhookIntakeUseCase`, no `WebhookController` (only `V108` + `WebhookEventStore` port + JDBC adapter, `47d24408`) | E4 spec §5.1–§5.3 (binding here) | R5–R6 |

### 2.C — Test & evidence debt

| ID | Debt (observed) | Rule (new, §5.5) | Fix |
|---|---|---|---|
| TD-1 | `CreatePaymentScenarioIT.java.disabled` (33 024 B, `modules/payments/src/test/java/io/dargent/payments/it/`) — the E3 scenarios are written and switched off | disabled test = debt | R1 |
| TD-2 | Two debug tests committed under `adapter/out/psp/` (incl. `HttpClientDebugTest`) — manual debugging aids, not specifications | evidence = CI test | R4 |
| TD-3 | `tasks/e3-acceptance-matrix.md` cites non-CI evidence; README demonstrates "create live" that does not exist; CHANGELOG claims E3 delivered; ledger E3 row cites the wrong run id (#9's id `33230405247` is E2's run); `.env.example` missing `CHAOS_PSP_LATENCY_MS`/`CHAOS_SEED`; E4 doc set never committed; design §8.2 sync note pending | commit msg = diff; honesty callouts | R7 |
| TD-4 | **False closure commit `97882494`** ("docs(e4): close webhook intake epic"): message announces `POST /webhooks/psp` + full-loop IT; the diff is docs-only (+263/−6, zero code). It committed `tasks/e4-acceptance-matrix.md` citing test classes that do not exist in the tree (`WebhookControllerIT.*`, `FullLoopIT.*`) with run #13 as their "evidence"; flipped the README honesty note to "the full loop works end-to-end"; flipped the ledger E4 row to ✅ | commit msg = diff; evidence = CI test; honesty callouts | R0 (void banner + README revert) / R7 (rebuild) |
| TD-5 | **Commit `765c4cc` message claims "E3/E4 ledger status updated to 'reopened (E3R)' in docs/epics.md"** — the diff does not touch `docs/epics.md` (0 deletions in stats). The reopened statuses never landed; `main`'s ledger asserts E3 ✅ ("commit a979c80, 73 tests pass" — pre-audit fabrication) and E4 ✅ ("full loop proven"), contradicting the E3R docs committed in the same push. First violation of the commit-message rule (§5.7) | commit msg = diff | R0 (corrected ledger commit) |
| TD-6 | Artifact index cites matrix files never committed: `tasks/e1-acceptance-matrix.md`, `tasks/e2-acceptance-matrix.md`, `tasks/e3-acceptance-matrix.md` are not in the tree (only `tasks/m0-acceptance-matrix.md` exists) | evidence = CI test | R7 (commit real matrices or correct the index rows) |
| TD-7 | **CHANGELOG anticipates inside the retraction entry** (HEAD `a678184`): the "Added: E3R Remediation epic" bullet asserts in the present tense "implements `POST /webhooks/psp`" and "re-evidences all matrix cells with CI tests" — both false at HEAD (webhook absent; matrices placeholder). The retraction entry itself carries the disease it retracts | commit msg = diff; honesty callouts | R7 (truth-scoped CHANGELOG: present only what the cited runs prove) |
| TD-8 | **README speaks three truths** (HEAD `a678184`): (a) honesty note credits create as "live (E3)" — the retracted epic — and says the webhook "lands with E4" (also reopened); the R0 revert restored a stale callout; (b) Current-state table marks **M1 ✅** ("webhook → CONFIRMED") contradicting the ledger's reopened rows; (c) Testing section asserts "the reconciliation scenario … runs in CI" — E5 does not exist; money-flow diagram is present-tense unmarked | honesty callouts; commit msg = diff | R7 (one voice: create live via E3R run #19; everything else marked with its landing epic) |
| TD-9 | **`tasks/e3r-acceptance-matrix.md` placeholder is garbled** (committed in `1c931f46`): every cell cites "run #15+" (non-evidence; real ids #18 `33282800600` red / #19 `33285295818` green absent); the register is **misquoted** (off-by-one from BD-4: `requestId` labeled BD-4, callback BD-8→labeled BD-8/BD-9 mix, read-side BD-10 labeled BD-9, E4 endpoint labeled BD-9); the story structure is the **rejected paraphrase** (R3 "PK on (merchant,key,endpoint)", duplicated "R6" snapshot sections, R7/R8 misnamed) — written from the paraphrase, not the committed spec | evidence = CI test; spec wins | R7 (rebuild cell-by-cell from spec §2 + backlog, real run ids only) |
| BD-11 | **Webhook processing runs WITHOUT a transaction in production wiring** (`db9d5b5`): `PaymentsCompositionConfig` wires `WebhookIntakeUseCase` with a pass-through `TransactionExecutor` (`Supplier::get`, comment: "no Spring TX manager needed for unit/IT") — E4 §5.3's "one transaction" is absent: confirm + outbox + audit are not atomic; a failure between confirm and outbox insert leaves the payment CONFIRMED with no `payment.confirmed` row, and replay-then-duplicate would never re-emit it (a money event lost silently) | E4 §5.3; AGENTS §3.2 | R6 part 3 (wire the real `TransactionTemplate` bean — the one the create path already uses — + atomicity proof: injected outbox failure → confirm rolled back, row stays RECEIVED) |
| TD-10 | **Handoff evidence not matching the tree** (`db9d5b5`, 2026-08-30): hygiene-grep output claiming 0 hits for `Instant.now()` in `apps/api/src/main` was pasted while `WebhookController.java` in that exact tree contains `java.time.Instant.now()` (twice, `persistRawAndRespond`); the report declared "closes MS-3" while **no webhook IT exists in the commit** (scenarios 6/7/8/10, ignored×3, full loop — none; run #23 proves compile + no regression only). Report ran ahead of the diff; greps stale or unexecuted at HEAD | commit msg = diff; evidence = CI test | R6 part 3 (fix code + re-run ALL greps at HEAD and paste outputs with the commit id they describe) |
| BD-12 | **Audit actor fabricated in the webhook path** (`ffc596c`, `WebhookIntakeUseCase` step 7): `auditWriter.record("confirm_from_webhook", UUID.randomUUID(), …)` — a random UUID as `actor_key_id` references an API key that does not exist (forensics poison). E4 §5.3 step 7 mandates `actor_key_id = null` (PSP callbacks have no API-key actor); this is BD-7's exact pattern reintroduced one commit after BD-7 was fixed in the create path — the commit message even documents it ("with generated actor_key_id") | E4 §5.3; BD-7 precedent | Block 3 step 0 (pass `null`; assert the audit row's actor is null in an IT) |
| BD-13 | **Hand-rolled JSON parsing in the money path** (`ffc596c`, `WebhookIntakeUseCase.parsePayload`): `payload_raw` is parsed via `indexOf`-based `extractJsonField` ("in production use Jackson" — the injected ObjectMapper already exists in the controller), and `Instant.parse(payload.paidAt())` runs **unguarded**: a signature-valid payload with a malformed `paidAt` throws inside the tx, leaves the row `RECEIVED` forever (permanent poison on every redelivery) | lesson #13; E4 §5.3 robustness | Block 3 step 0 (inject ObjectMapper, strict parse; malformed fields → `IGNORED`, never poison) |
| BD-14 | **V106 diverges from both specs on the audit actor** (verified raw at `f11cd2c`): `V106__audit_log.sql` declares `actor_key_id uuid NOT NULL`; E3 spec §3.2's table def has no NOT NULL and E4 spec §5.3 step 7 mandates `null` for PSP callbacks. The conflict surfaced at BD-12's fix and was resolved **in-block by improvisation** (nil-UUID sentinel `WEBHOOK_AUDIT_ACTOR = 00000000-…`) instead of stop-and-report — the sentinel is forensically defensible (deterministic, greppable system actor) but unratified, and the divergence is unrecorded in any doc | schema↔spec rule; stop-and-report | **Owner adjudication** (recommendation: accept sentinel as the documented system actor; amend E4 §5.3 step 7, record the convention in R7/R8 + data-model-decisions; alternatively a future expand migration may make the column nullable — owner's call) |
| TD-11 | **Commit message claims not carried by the diff** (`f11cd2c`): message asserts "Instant.parse guarded" — the `Instant.parse(payload.paidAt())` at that HEAD is **still bare** (verified line-level); message asserts "BD-11 guard: atomicity_happy_path IT" — that IT is the pre-existing happy-path test from `ffc596c`, which **cannot distinguish a real TransactionTemplate from a pass-through executor** (atomicity only manifests on failure); the failure-injection IT remains absent. Third instance of the message-accuracy violation class (after TD-5, TD-10) | commit msg = diff | Block 3 step 0 remainder (finish BD-13 guard + failure-injection IT; every message claim re-checked against `git diff` before push) |
| TD-12 | **Closure report ahead of the tree — second instance, at epic-close severity** (handoff 2026-08-30 ~19:35Z): the Block 3 handoff declares "E3/E4/E3R all ✅ on main (run #29 `33331033505` green)", "R7/R8 Complete" (three matrices, README, CHANGELOG, AGENTS.md, lessons #14, hygiene greps) and "Register zeroed / Ledger flips" — but the chain `f11cd2c…main` (`1e9dec6`) contains **exactly three code/test commits** (`0eeda42` BD-14, `7abee75` BD-13 residual, `1e9dec6` BD-11 guard) and the aggregate diff touches **only 2 files** (`WebhookIntakeUseCase.java` +19/−6, `WebhookIntakeIT.java` +72/−0). `docs/epics.md` at HEAD still reads E3 ◐ reopened / E4 ◐ reopened / E3R ◐ spec published — **no flips exist on main**; no matrix/README/CHANGELOG/AGENTS/lessons commit exists. Also inside the handoff: the `0eeda42` message claims "E4 §5.3 step 7 amended" with **no docs file in any diff** (4th message≠diff instance, after TD-5/TD-10/TD-11), and it cites "run #28 `33331033505`" — that id is **run #29**. Same class as the Block 2 "all done" that outran the push | commit msg = diff; evidence = CI test; "done" = pushed + green run id | Determine where R7/R8 live (local unpushed commits vs never written — `git log origin/main..main` decides); land them for real; flip changeset **last**; final run id cited at that HEAD; re-handoff |
| TD-13 | **R7's citation layer fails the epic's own evidence standard** (`3b60ba8`, verified line-level — substance independently confirmed real): (i) `tasks/e3r-acceptance-matrix.md` **reintroduces TD-9's off-by-one register misquote** (requestId→"BD-4", snapshot→"BD-5", random actor→"BD-6", String.format→"BD-7", callback→"BD-8", read side merged into "BD-9") **and the rejected-paraphrase R-structure** (R3 "PK on (merchant,key,endpoint)", duplicate "R6" sections, R7/R8 renumbered as auth/pagination proofs — the committed spec's R7=docs truth, R8=governance); (ii) the register-traceability table **omits BD-10…BD-14 and TD-7…TD-11** (the items Block 3 existed to close); (iii) systematic run-number↔id mispairings in the matrix, the ledger flip rows and the correction note — `33288538459` labeled #15/#17/#22 (it is **#20**, A0 golden on `c2809c1`); `33321575303` double-cited as #25 AND #26; BD-13 residual cited #25 (landed `7abee75` = **#28** `33329581906`); BD-11 failure-injection cited #26 (landed `1e9dec6` = **#29** `33331033505`); `33331033505` labeled "#28" in the E3/E4/E3R flip rows + correction note; "mvn green (run #28 `33328906357`)" (that id is **#27**); the final run **#30 `33333739409`** never cited; (iv) artifact index still says the e3 matrix is "not yet committed (TD-6)" inside the very commit that commits it. Verified run-id↔number table lives in `e3r-block1-verification.md`. The evidence EXISTS (every fix, test and run verified real); its labels are wrong | §5.6 (matrix cell = test name + run id); TD-9 precedent; commit msg = diff | One docs-only correction commit: fix matrix register ids + R-structure from the committed spec, restore BD-10…BD-14/TD-7…TD-11 traceability rows, reconcile every run id↔number pair from the verified table, update the artifact index, paste hygiene-grep outputs with their commit id. No code, no re-flip (flips verified TRUE); rides as E6 block step 0. **RESIDUAL (2026-08-31, E6 S0 audit of `e6d8751`): ~70% closed** — rows BD-10…BD-14/TD-7…TD-11 added with CORRECT pairs, `33288538459`→#20 propagated in the register body; BUT the off-by-one survives in rows requestId="BD-4"…read side="BD-9"; the rejected-paraphrase R-structure survives (R3 idempotency-PK, duplicate R6, R7/R8 as auth proofs); R0 table still cites #15/#17 = `33288538459`; TD-10 row is mislabeled ("missing coverage" — TD-10 is the stale-grep defect) and re-mispairs #22 = `33288538459`; TD-12/TD-13 absent from the traceability table; **ledger `docs/epics.md` E4 row (found 2026-08-31 @`e6d8751`): "run #26 `33331033505`" — that id is run #29 (failure-injection, `1e9dec6`); the true #26 `33326648770` (sentinel) is absent from the row**. **Owner order (2026-09-01): fix IMMEDIATELY (docs-only), before any new commissioning — not riding E10** |\n| TD-14 | **E7 S0 skipped and dressed as done** (e7 matrix @`da67f52`, S0 row): claims "TD-13 residual fixed" citing `e6d8751` / run #48 — E6's post-flip citation commit, which **predates the residual's discovery** (found in the E6 closure audit of `e6d8751` itself) and predates E7's commissioning; chronologically impossible, residual items untouched. Fourth handoff with an evidence-layer falsehood (pattern TD-5 → TD-11 → TD-13 → TD-14) | commit msg = diff; stop condition "a cell without real evidence stays open and says so" | Immediate docs-only commit (owner order 2026-09-01): (1) e3r matrix residual items per the TD-13 list; (2) e7 matrix S0 row rewritten honestly (deferred-to-now; this commit's run pair cited); (3) ledger E4 row pair corrected (#29 `33331033505`, add #26 `33326648770`) |
| BD-15 | **Money-loss window in ledger intake — BD-11's class reborn** (deep-read `EventIngestionUseCase` @`6897d1d`, 2026-09-01): the dedupe insert (terminal state RECEIVED) commits OUTSIDE the posting tx; `insertEventIfAbsent` returning false → ack-skip **regardless of stored status**. A failure (DB blip, constraint, crash) between insert-RECEIVED and `postJournal` commit leaves the row RECEIVED forever; the SQS redelivery hits the duplicate branch and is **acked without posting** — the money event is silently lost, and the proof stays green (no postings = no divergence; only a stale RECEIVED row remains). The e7 matrix race table even documents the wrong recovery ("re-delivered → dedupe skip") | AGENTS §3.3/§3.4; BD-11 precedent (failure between write steps loses the event silently); at-least-once contract | Owner decision on timing (recommendation: BEFORE E10, money-loss class): on duplicate, re-read status; RECEIVED → idempotent resume of posting (RECEIVED→POSTED transition) instead of ack-skip; + failure-injection IT (post fails once → nack → redeliver → POSTED, one journal row) — the BD-11 guard medicine | **[CLOSED 2026-09-01 — fix block audited]** fix `3ae463e` (#72 `33555099220`) + citation `5b70ae1` (#73 `33556352180`, run unregistered per #57/#67 precedent); audited line-level @`de54825`: duplicate branch re-reads `findEventStatus`; RECEIVED → `resumePosting` (1 tx: conditional `claimEventForResume`, 0 rows → ack-skip zero writes; postings §5.3 + `postJournal`; catch `DataIntegrityViolationException` → re-read → POSTED → ack, else rethrow); POSTED/IGNORED/REJECTED → ack-skip. Unit matrix + real-DB guard IT green (#75 `33560118008` @HEAD). Residual **BD-15R** opened (guard IT failure-injection leg) |
| BD-16 | **Jackson 2 (`com.fasterxml.*`) in ledger production sources** (`EventEnvelopeReader` @`6897d1d`): imports `com.fasterxml.jackson.core/databind` + `JavaTimeModule` (Jackson 2 line) while the house standard since lesson #13 is Jackson 3 (`tools.jackson.*`); the class javadoc even says "Strict Jackson 3 reader" — message≠code at comment level. E7's hygiene greps (annotations/AWS/boundaries) never covered the Jackson rule, so 71 green runs never saw it. Minor companions: a second `new ObjectMapper()` per `extractPaymentPayload` call; `Instant.parse`'s `DateTimeParseException` is NOT an `IllegalArgumentException`, so a malformed `occurredAt` escapes the reader's catch clause by type (lands in DLQ by accident, not contract) | lesson #13; E3R §7 hygiene ("no com.fasterxml.jackson in prod"); commit msg = diff | Fold into the BD-15 fix commit: switch reader to `tools.jackson` ObjectMapper (reuse the injected/field mapper), normalize the parse exception, extend the hygiene grep to `com.fasterxml` in `modules/ledger/src/main` | **[CLOSED 2026-09-01 — fix block audited]** fix `e946a15` (#74 `33559514160`; pom swap `tools.jackson.core:jackson-databind:3.1.5` + `jackson-core:3.1.5`, disclosed in the commit message) + citation `de54825` (#75 `33560118008`); audited line-level @`de54825`: `tools.jackson.*` only, ONE field mapper, `parseInstant` catches `DateTimeException` → IAE, readTree wraps → IAE in both methods, poison proof `malformed_occurredAt_is_poison_by_contract`, default ctor in tests; matrix hygiene row: `grep com.fasterxml modules/ledger/src/main` = 0 hits @`e946a15` |
| TD-15 | **README honesty regression — TD-8's lie survives R7; found by the 3rd external analysis, confirmed and extended** (README @`6897d1d`): (i) Testing section asserts "the reconciliation scenario ('webhook suppressed → reconciler confirms') runs in CI" — **the exact TD-8 sentence**, still false: the reconciler is E5 (☐); E2 proves drop-once-without-retry, nothing confirms on its own; (ii) bold subtitle "A payment infrastructure backend in the shape of platforms like Stripe/Razorpay" invites the product-equivalence reading a payment reviewer makes (it is an architecture analogy, not scoped as one); (iii) the money-flow block narrates refunds, reconciler, resurrection and "notifications notified" in present tense with no target-state marker — mitigated by the intro hedge + Current-state table (M3 "Suffering" ☐), but coding-standards §10 makes present-tense future a defect by our own rule; (iv) stale comment "will show PENDING until E4 webhook arrives" (E4 is complete; the wait is the PSP's webhook) | coding-standards §10; TD-8 precedent; commit msg = diff | Docs-only commit riding the BD-15/16 fix block: recast the reconciliation sentence (E2's drop proof is real; recovery lands with E5), mark the money-flow block as target state, scope the subtitle as architecture analogy, fix the stale E4 comment; cross-check every README claim against the Current-state table | **[CLOSED 2026-09-02 — E10 step 0a audited]** commit `5919275` (#76 `33566863734`): all four items landed (subtitle→"architecture analogous"; reconciliation→"future reconciliation job (E5, not started)"; money-flow header "TARGET STATE" + reconciler "(E5, future)"; stale E4 comment fixed); message = diff |
| BD-15R | **Residual of the BD-15 fix — guard IT lacks the adjudicated failure-injection leg** (audit @`de54825`, 2026-09-01): the guard IT `redelivery_after_posting_failure_resumes_and_posts_exactly_once` (LedgerMoneyLoopIT) hand-seeds the RECEIVED row and proves resume + exactly-once on real PG (1 journal, 3 postings, exact balances, proof ok) — a valid regression guard for the ack-skip defect. But the Q1/Q2 adjudicated mechanics (inline trigger `fail_journal_insert` RAISE on `ledger.journal_entries`, create → first delivery nacks leaving RECEIVED → drop mid-test + `@AfterEach` safety net → redeliver resumes) were NOT implemented: the failure→RECEIVED transition is never exercised end-to-end, so a future refactor that stops persisting RECEIVED before posting (or breaks the first-delivery nack) would keep this test green | Q1/Q2 adjudications in `tasks/ledger-e7-fix-prompt-bd15-bd16.md`; §9d (diverged without prior stop-and-report; disclosed honestly in the handoff — TD-14 respected, TD-11 class avoided) | LOW, test-only rider on E10: extend the existing guard IT with the trigger leg exactly as adjudicated (create trigger → nack leg asserts RECEIVED + zero journal + nack → drop mid-test → redeliver asserts resume exactly-once; `@AfterEach DROP TRIGGER IF EXISTS trg_fail_journal_insert`) | **[CLOSED 2026-09-02 — E10 step 0b audited]** commit `c1b435c` (#77 `33568197213`): leg implemented per adjudication — inline DDL (function `fail_journal_insert_once` RAISE 'SIMULATED_POSTING_FAILURE', trigger `trg_fail_journal_insert` BEFORE INSERT on `ledger.journal_entries`), leg 1 asserts throw + row stays RECEIVED + zero journal, drop mid-test, leg 2 asserts ack + exactly-once (1 journal, 3 postings, 4900/100/−5000, proof ok, POSTED), try/finally + `@AfterEach` cleanup |
| TD-16 | **False citations in the E10 Block 1 handoff + commit `db82f60` message** (audit 2026-09-02, main `8138f4c`): (i) `NotificationPoisonDlqIT` cited as "disabled with @Disabled" — the file NEVER existed; spec §8.2 poison/DLQ scenario has zero coverage; only `NotificationLoopIT` exists with ONE test method (happy path). Same class as the fabricated E4 matrix (`97882494`); (ii) run mislabels: S1 cited as `30245af` #80 — actually `30245af` = **#79 RED** (green S1 is `d47eec4` #80, which the handoff attributed to S4); S4 = `db82f60` **#83 RED**, never cited green; headline "CI green (#84)" conceals two red runs (P1 required citing them); (iii) "E10 Block 1 (S1–S4) complete" refuted: S4 blocked by BD-17, S5 missing | E3R precedent (R7/R8 absent = closure handoff VOID); lesson #14 (green CI ≠ right code); commit msg = diff | Docs-correction commit (engineer, own pair + grep post-commit citing SHA): canonical table rows #76–#84 incl. reds, false citations retracted, S5 declared missing; Block 1 re-closed honestly only after the remediation block lands | 
| BD-17 | **Production wiring depends on a test-only bean — notifications consumer can never boot in prod** (audit @`8138f4c`): `NotificationCompositionConfig.sqsNotificationConsumer` (MAIN) injects `@Qualifier("notifsTestSqsClient") SqsClient`; the only bean with that name lives in the IT's `@TestConfiguration` (`NotificationLoopIT:379`). With `DARGENT_NOTIFS_CONSUMER_ENABLED=true` the app context fails (`NoSuchBeanDefinitionException`); CI never enables the consumer, so the defect is invisible — and the LoopIT (which overrides the bean anyway) was disabled instead of exposing it. Ledger comparator shows the correct pattern (`ledgerSqsClient`, no qualifier) | playbook prime directive ("a test that mocks our own infrastructure proves nothing"); BD-11 wiring class; E3R lesson | Remediation block 1.5 (test-only): wire the consumer to the production `notifsSqsClient` bean (mirror ledger), make LoopIT drive the MAIN wiring exactly like the ledger ITs do, enable it, fix the LocalStack fan-out for real, cite red+green pairs | 
| BD-18 | **Pre-existing `@Disabled` on `DevApiKeyProvisionerTest`** ("Flyway context loading issue — to be fixed in S8"), found 2026-09-02 while auditing the block; NOT touched by E10 — predates it (E3-era api test); "S8" refers to no live plan; a disabled test guarding provisioning (money-adjacent) violates the skips rule and was never registered | standing rejection of skips/@Disabled; playbook §7 (quarantine requires a declared debt entry) | Rider on a near block: either fix the Flyway context loading and enable, or register quarantine debt with a target milestone — owner picks | **[Owner decision 2026-09-02]** fix as test-only rider in the 1.5 remediation block (root-cause the Flyway context loading, enable; main-code change needed → stop-and-report) |
| TD-17 | **E10 spec §7 conflicted with AGENTS §3.7** (caught 2026-09-02 by the engineer's stop-and-report while drafting Block 2): the spec required a `merchantId` query param on `GET /v1/notifications`, violating "the tenant comes from the credential, never from path/query/body". AUTHOR-SIDE defect: the governance side wrote the divergent §7 — the working protocol (§9d) caught it before any execution | AGENTS §3.7 (non-negotiable) governs specs, not the reverse | **[CLOSED 2026-09-02]** §7 amended in the governance workspace (principal-scoped reads, no merchantId param; `merchant_id` in items is output-only; §8.3 gains a cross-tenant isolation proof); lands with the owner's governance commit |
| TD-18 | **E10 spec §7 response fields were snake_case** (`event_id`, `occurred_at`, `next_cursor` — Stripe wire mimicry) against the house camelCase wire convention (PaymentController + shared envelope fixtures `eventId`/`occurredAt`). Second author-side spec defect caught by the engineer's stop-and-report during Block 2 drafting (2026-09-02), again before any execution | Clean Code: consistency/least-surprise at the seam — one JSON convention per API surface; SOLID's boundary policy applied uniformly, not per endpoint | **[CLOSED 2026-09-02]** §7 amended in the governance workspace (camelCase fields + `nextCursor`; naming-guard assertion added to §8.3: response has `eventId`, never `event_id`); lands with the owner's governance commit |
| TD-19 | **M2 flip landed on the wrong document** (closure audit @`be91286`, 2026-09-02): `fa00eb3` flipped README ("M2 is now ✅") + CHANGELOG + spec matrix — but `docs/epics.md`, the CANONICAL ledger, still shows E10 ☐ and E7's row still says "M2 stays ◐ until E10". epics.md's own convention requires the row flipped "in the same change set"; README and canonical ledger now contradict each other | single canonical ledger rule; epics.md conventions footer; TD-8 class (doc claiming a state the governing doc denies) | Docs-only: flip E10 row ✅ + M2 ✅ + clean E7's note | **[Owner decision 2026-09-02]** flip lands in the OWNER's governance commit (exact row edits pre-built in `tasks/e10-m2-flip-governance-patch.md`); run green after push | **[CLOSED 2026-09-02 — verified @main]** owner commit `368e76c4a8cf` ("governance: land E10/M2 flip patch + rider prompt (TD-20)"): epics.md E10 row ✅ 2026-09-02 with the audit chain cited, E7 stale note replaced by "M2 ✅ (closed with E10, 2026-09-02)", patch + rider prompt files landed; README↔ledger contradiction resolved |
| TD-20 | **S6 acceptance deviations from TD-17/TD-18 adjudications** (closure audit @`be91286`): (i) `merchantId` dropped from the RESPONSE (shape test asserts `has("merchantId")).isFalse()`) although TD-17 ruled the output echo STAYS; (ii) cross-tenant isolation test MISSING (TD-17 §8.3: seed 2nd merchant, assert never returned); (iii) naming-guard negative absent (TD-18: response must not contain `event_id`; positives are asserted, negatives are not). Disclosed in the handoff as facts, not asked as adjudications; code itself is conformant (principal-scoped, camelCase) | §9d; TD-17/TD-18 rulings | Owner picks: small test/docs rider vs register as accepted debts | **[Owner decision 2026-09-02]** FULL rider: cross-tenant isolation test + naming guard + `merchantId` RESTORED to the response (as originally adjudicated in TD-17) — prompt `tasks/notifications-e10-rider-prompt-td20.md`; closes on audit of the rider's pairs |
| RAT-E10-IT | **As-built ratification request — notifications IT altitude mirrors the ledger ITs, not the 1.5 prompt text** (audit @`be91286`): the 1.5 contract said "IT drives the MAIN wiring"; the engineer kept `@TestConfiguration` test-client/test-consumer beans (use-case-driven legs, Q1 dedupe form honored) — the same harness pattern the LEDGER ITs actually use; the premise of the contract text ("like the ledger ITs do") was auditor-side wrong. Production defect BD-17 IS fixed (prod bean graph clean); equal footing with ledger = consistent | BD-17's substance; ledger-harness precedent; §9d | Owner-approved seal requested (V202–V207 precedent) | **[CLOSED 2026-09-02 — owner-approved]** seal granted: IT altitude (ledger-harness mirror) ratified as-built; process note on #117 self-commissioning accepted with mitigation (owner-relayed adjudications + combined handoff) — no rework, no formal item |
| DEBT-4 | **Coverage gap: nothing detects a confirmed payment without a journal entry** (4th external analysis, claims verified @`8138f4c` 2026-09-02): the proof is ledger-internal (ΣDR=ΣCR, projection==SUM); payments can confirm while the ledger consumer is off (contract default `DARGENT_LEDGER_CONSUMER_ENABLED=false`) or an event is lost before intake — no reconciliation asserts every CONFIRMED payment has exactly one journal entry. "Confirmation without journal" is an incident nobody would see today | 4th analysis's strongest point ("the ledger is a projector of payment.confirmed, not the arbiter"); at-least-once intake covers redelivery, not never-delivered | Recommendation: E5 reconciler gains a coverage leg (confirmed ⇒ journal; dangling on either side = incident, `Idempotent-Replay` semantics untouched). Owner ratifies at E5 commissioning |
| DEBT-5 | **Balanced-entry invariant exists nowhere before the write** (4th external analysis, verified line-level): `JournalEntry` ctor is a field bag — no Σ debits = Σ credits, no ≥2 postings, no amounts > 0 check (`netAmountCents()` sums signed and returns); no DB barrier (no deferred constraint/trigger); `verifyProof()` only denounces after the fact — a use-case bug persists garbage until the daily proof. Companion facts verified: settlement stores its idempotency key in the journal `txid` field (semantic overload, works); `postJournal` nested `txTemplate` joins the outer tx (REQUIRED) — critique's concurrency scare overstated | "the ledger must refuse impossible state by itself"; double-entry mechanics (reversal entries, not edits) | Recommendation: ctor validation + DB deferred trigger as E8 rider (refund entries [3]+[4] make the guard load-bearing); fold the `txid`-overload fix and "payout = reconciliation with a real cash account, not `payouts:external` evaporation" into E8's design seed. Owner picks scope at E8 commissioning |


### 2.D — Explicitly NOT defects (verified correct — do not "fix")

- The GET detail endpoint recomputing the BR Code on read is **spec behavior** (E3 §5.2), not a defect.
- `limit` clamp (1–100) and `nextCursor` when `size == limit` match §5.3.
- E1/E2 stand: domain, simulator, HMAC scheme, chaos knobs — the audit confirmed them solid.
- V103–V107 stand (S0 verifies V108 only, since it landed without its review pass).

## 3. Scope

### In scope
- R1–R4: create path remediation (BD-1…BD-10, MS-1, MS-2, TD-1, TD-2);
- R5–R6: webhook intake implemented for real per E4 spec §5.1–§5.4, including its ITs and the full-loop IT;
- R7: documentation honesty pass — README, CHANGELOG, ledger (`docs/epics.md`), `.env.example`, design §8.2;
  `tasks/e3-acceptance-matrix.md` rewritten; `tasks/e4-acceptance-matrix.md` and `tasks/e3r-acceptance-matrix.md`
  created; every cell cites a CI test name + run id;
- R8: governance — AGENTS.md amendments (§5.5/§5.6, §7 rule, DEBT-3 row), `docs/lessons.md` entry;
- Committing the previously uncommitted doc sets (E4 set + this E3R set) in the same changesets as the code
  they document.

### Out of scope
- E5 (expiration/reconciliation) — stays blocked until E3R closes; E6+ unchanged;
- Any new Flyway migration by default: **V103–V108 stand as-is**. S0 audits V108 against E4 spec §5.4; only a
  proven divergence justifies an expand-only V109 (record the deviation; do not edit V108 — forward-only rule);
- Refunds, ledger, notifications, relay: untouched; no changes to `apps/psp-simulator` (the simulator is the
  audit's reference implementation of the webhook scheme — its `WebhookSigner` is scheme documentation, never
  an import);
- No git history rewrite (audit recommendation rejected previously — stands);
- No branch protection / repo hardening (that is E3.5, still not executed; unchanged).

## 4. Architectural constraints

### 4.1 Package shape (only what was missing gets created)

```
modules/payments
├── application/             CreatePaymentUseCase        ← fixed in place (R2), behavior per §5.1
├── domain/model/            + WebhookSignatureValidator (pure, TDD, R5)
├── application/             + WebhookIntakeUseCase      (one transaction, TDD, R6)
├── adapter/in/rest/         PaymentController           ← gains POST /v1/payments; read side fixed (R3)
└── adapter/out/psp/         SimulatorChargeAdapter      ← retry D19 + read-back + PSP truth (R2)
apps/api                     + PaymentController, WebhookController (R6 — see placement note)
                             + wiring only (create path bean, webhook controller scan)
```

> **Controller placement (DEV-R6, adjudicated 2026-08-30):** the original §4.1 placed `WebhookController` in
> `modules/payments/adapter/in/webhook/` while also requiring the single `ErrorResponseWriter` — which lives in
> `apps/api` (`io.dargent.api.error`), and modules never depend on apps (AGENTS §2). The two clauses cannot
> coexist; the as-built convention since E3 is that the entire inbound HTTP layer (controllers, filters, error
> writer, codec) lives in `apps/api`. `WebhookController` therefore lands in
> `apps/api/.../controller/` beside `PaymentController`. Validator (domain) and intake use case (application)
> stay in `modules/payments` — they import domain and ports only. The empty `adapter/in/webhook/` package is
> never committed. R7 syncs design §8.2 and E4 §3.1 with this; R8 reconciles AGENTS §2.2 language with the
> as-built convention (inbound HTTP adapters in the boot app, modules own domain/application/outbound adapters).

Existing and correct (do not rebuild): error contract classes, API-key security (7 classes), provisioning,
`RequestIdFilter`, `CursorCodec`, `BrCode` + domain tests, V103–V107, `WebhookEventStore` port + JDBC adapter.

### 4.2 Config surface — env names are contract, unchanged

| Property | Env | Note |
|---|---|---|
| `dargent.psp.base-url` | `PSP_BASE_URL` | exists; adapter must actually read it |
| `dargent.psp.callback-url` | `PSP_CALLBACK_URL` | **BD-9 fix: this value, never a literal** |
| `dargent.psp.create-max-attempts` | `PSP_CREATE_MAX_ATTEMPTS` | BD-4 fix: retry loop bound (default 3) |
| `dargent.psp.create-backoff-base-ms` | `PSP_CREATE_BACKOFF_BASE_MS` | BD-4 fix: linear backoff base (default 200) |
| `dargent.pix.profile.*` | `DARGENT_PIX_KEY` / `DARGENT_RECEIVER_NAME` / `DARGENT_RECEIVER_CITY` | BD-10 fix: BR Code composes from these |
| `dargent.psp.webhook-secret` | `PSP_WEBHOOK_SECRET` | E4 §3.2 — same value both apps in compose |

Zero new env names. Zero new dependencies (WireMock standalone is already test-scoped in `modules/payments`).

### 4.3 Binding sources for the webhook side

`tasks/webhook-intake-e4-spec.md` §1–§9 is the binding contract for MS-3: pipeline order (§5.1), validator and
vectors (§5.2), processing transaction (§5.3), V108 shape (§5.4). The E4 backlog/sequence/prompt are superseded
by E3R (their step-0 gates and scope assumptions predate the audit).

## 5. Exact contracts

### 5.1 `CreatePaymentUseCase` — remediated behavior (binding script)

The command context carries `(merchantId, apiKeyId, idempotencyKey, requestFingerprint, requestId, command)`.
`requestId` = the validated `X-Request-Id` (generated UUID when absent); `apiKeyId` = the authenticated
principal's key id — **never** generated.

1. **Core transaction** (`TransactionTemplate` — BD-1): insert `idempotency_keys` `IN_FLIGHT` (PK violation →
   loser re-reads → `425 idempotency_key_in_flight` + `Retry-After: 1`) → `Payment.create(txid, merchantId,
   amount, description, expiresAtRequested, clock.now())` → `PaymentRepository.save` (duplicate txid → bounded
   regeneration ≤ 3) → outbox row (`payment.created` envelope, §5.2) → audit row (`command_name=create_payment`,
   `actor_key_id = apiKeyId` — BD-7) → commit. Aggregate lands **`PENDING`** (BD-2).
2. **PSP phase, strictly after commit** (BD-4/D19): `PspPort.createCharge(txid, amountCents, expiresAt,
   callbackUrl = PSP_CALLBACK_URL resolved, description)` — connect 2 s / read 5 s; up to
   `PSP_CREATE_MAX_ATTEMPTS` attempts, linear backoff `base × attempt` via the **injected sleeper**; retryable =
   connect/read IO and 5xx; **409 `txid_already_exists` is not retryable** → read-back `GET /cobs/{txid}`.
3. **Success tx** (BD-3): a **second short transaction** that conditionally updates the payment from the PSP
   response (`expires_at` = PSP truth) **and** moves the idempotency row to `COMPLETED` with the real snapshot
   (`response_status` + exact 201 body — BD-6). The aggregate is re-read/re-attached in this tx; the stale
   pre-PSP instance is never written; the expected version comes from the row just read, never a literal.
4. **Exhaustion tx** (BD-3/BD-4): mark `FAILED("psp_create_exhausted")` via `updateIfVersionMatches` with the
   re-read aggregate's current version (lost race → re-read → decide); append a `PaymentFailed` outbox row
   (serialized via the shared Jackson-3 serializer — BD-8); **delete** the idempotency key row (audit_log keeps
   the trail); answer `502 psp_unavailable`.
5. Replay = byte-equal snapshot, `Idempotent-Replay: true`, zero side effects; same key + different fingerprint
   = `409 idempotency_key_conflict` (E3 §5.1.3 table, all five rows, unchanged).

### 5.2 Outbox envelope (unchanged shape — remediated construction)

`{ "eventId", "type": "payment.created"|"payment.failed", "version": 1, "aggregateId": txid, "merchantId",
"requestId": <BD-5: the real request id>, "occurredAt", "payload": {…} }` — serialized **once** through the
shared Jackson-3 serializer with deterministic key order (BD-8). No `String.format` JSON anywhere in prod sources
(grep gate in R7).

### 5.3 `POST /v1/payments` (MS-1/MS-2)

Contract = E3 spec §5.1 **verbatim** (request shape, validation order and field maps, `Idempotency-Key` 8–200,
`201 Created` + `Location: /v1/payments/{txid}` + `X-Request-Id` echoed, response body with PSP-true `expiresAt`
and the composed BR Code). Security per AGENTS §4.1: the route exists in `SecurityConfig` under `/v1/**`
authenticated. The controller composes the BR Code from the configured PIX profile (BD-10), never literals.

### 5.4 Read-side corrections (BD-10)

`GET /v1/payments/{txid}` / `GET /v1/payments` keep §5.2/§5.3 shapes. Fixes: PIX profile from config; `Clock`
injected (no `Instant.now()` in controller code); the cursor is decoded **once** and the decoded keyset
`(txid, createdAtMicros)` — not the raw string — is passed to `findPage`.

### 5.5 Webhook intake (MS-3) — E4 spec §5.1–§5.4 binding

Pipeline order is binding: capture raw once → validate (fail-closed: persist raw + `401 invalid_signature`) →
anti-replay 300 s (persist raw + `401 signature_expired`) → persist `RECEIVED` → process (dedupe
`provider_event_id = endToEndId + "|" + type` unique; unknown type/txid/amount-mismatch → `IGNORED` + `200`;
confirm via E1 `confirm(endToEndId, FeeBreakdown.of(amount, BpsRate.of(100)), paidAt)` with conditional UPDATE;
`payment.confirmed` outbox row with `payload {amount, fee, net, late}`; audit row; `PROCESSED`). Vectors asserted
byte-exact (recomputed before writing the tests — audit rule):

```
secret = dev-only-secret
vector 1: ts = 1787932800
  body = {"eventId":"psp-evt-test-001","type":"payment.confirmed","txid":"8KD4Z9X2Q7W1M5T3R6Y0A1B2C","endToEndId":"E9040381234567890123456789012345","amount":10000,"paidAt":"2026-08-29T00:00:00Z"}
  sign("1787932800", body) = 549eabc4c6f862fdb9322861f43091039de9c75de8107a60945d464755549113
vector 2: sign("1", "{}") = e3f75e30c05fa6ab20d1cdd115d4172f6adba335dca3ed37842195aa05305529
scheme: HMAC-SHA256(secret, UTF-8(ts + "." + rawBody)), lowercase hex, constant-time compare, ±300 s
```

Test-local hand-signer only — **never** import `WebhookSigner` from the simulator (E4 rule, restated).

### 5.6 Ledger & documentation truth (TD-3) — exact diffs for `docs/epics.md`

**Baseline for these edits is the ledger as it exists on `main` at `765c4cc`** (verified raw): the E3 row is
currently `✅ … (commit a979c80) … 73 tests pass`, the E4 row is `✅ … run #33267438415 … full loop proven`, the
artifact index carries only E0/E1/E2, and the workspace copy of this file is stale — always edit from the repo's
content. Applied in the same change set as R0 (statuses, correction note) and finalized in R7/R8 (closures).
Row texts:

- **E3 row →** `◐ reopened (E3R) — 2nd external audit (2026-08-29): POST /v1/payments absent over HTTP
  (PaymentController ships GETs only); CreatePaymentUseCase violates spec §5.7/§5.8 (defect register,
  E3R spec §2); CreatePaymentScenarioIT shipped disabled. Prior matrix/README/CHANGELOG claims void.
  Remediation = E3R`
- **E4 row →** `◐ reopened (E3R) — audit: only V108 + WebhookEventStore port/adapter landed (47d24408);
  validator, intake use case, POST /webhooks/psp absent; E4 doc set never committed. Remediation = E3R`
- **New E3R row (after E4):** `| E3R | Remediation: create path + webhook intake (audit pass) | payments, api |
  E1, E2 (remediates E3+E4) | M1 | ◐ spec set published — blocks E5 |`
- **E5 depends-on cell →** `E3R (E3+E4 remediated)` · **legend gains:** `◐ reopened = documented as closed but
  refuted in code (audited; remediated via E3R)` · **artifact index gains E4 + E3R rows** · **briefs updated**
  (E3/E4 marked reopened with one-line audit notes; E3R brief added).
- README callout flips only when the evidence exists; until then it states the truth: *create and webhook land
  with E3R — the earlier "live" claim was wrong*. CHANGELOG gets a correction entry (retraction), not a silent
  edit. `.env.example` gains `CHAOS_PSP_LATENCY_MS` + `CHAOS_SEED` (E2 follow-up, declared and never landed).

### 5.7 Governance amendments (R8 — exact text)

**AGENTS.md §5 (testing rules), append:**

- `5.5. A disabled or skipped test is debt: register it in §8 with an owner and a target milestone. A disabled
  test is never acceptable evidence in an acceptance matrix. Re-enabling a disabled test that then fails means
  the code violates the spec — fix the code; editing the test's expectations to get green is a defect.`
- `5.6. Acceptance-matrix evidence is a test that runs in CI, cited by test name + run id. A cell citing code
  review, local-only runs, or a disabled test is void.`

**AGENTS.md §7 (commits), append:** `- A commit message describes exactly the diff it contains. It never
announces work the diff does not carry; required follow-ups become tasks, not promises in messages.`

**AGENTS.md §8 (debt), add:** `| DEBT-3 | E3/E4 closed on paper before the code existed (audit 2026-08-29):
endpoint missing, use case off-spec, scenario IT disabled. Root causes: evidence not required to be CI tests;
commit messages allowed to outrun diffs. Paid by E3R; rules §5.5/§5.6 + §7 prevent recurrence. | E3R | M1 |`

**`docs/lessons.md`, new entry:** `#14 — Green CI proves that tests pass — not that they are right, and not that
the code exists. E3 closed with a green run over a changeset whose endpoint was never written and whose proving
IT was disabled. Matrix evidence must be a CI test; the first act of remediation is re-enabling the disabled
specification and watching it fail.`

## 6. Concurrency & races (all proven by tests — no new arbitration designs)

| Race | Arbitration | Proof |
|---|---|---|
| Two concurrent creates, same key | PK violation on `idempotency_keys`; loser → 425 | scenario 15 IT (barrier, 4 threads) |
| PSP create already exists (409) | not retryable → read-back | WireMock IT (BD-4 fix) |
| `markFailed`/PSP-truth lost race | conditional UPDATE with the re-read aggregate's version; loser re-reads | unit + IT (BD-3 fix) |
| Duplicate webhook while processing | `provider_event_id` unique → re-read → `PROCESSED`/`RECEIVED` decision | E4 §6 ITs (twice sequentially + 2 threads) |
| Confirm lost race (webhook vs any confirm path) | conditional `updateIfVersionMatches`; loser → `duplicate` | unit (fake returns false) — lesson #12 pattern |
| Replay of `payload_raw` after crash | row `RECEIVED` → reprocess → same result once | scenario 10 IT |

## 7. Testing requirements

- **R1 is red by design:** `CreatePaymentScenarioIT.java.disabled` → `CreatePaymentScenarioIT.java`, run on CI;
  the red run id is recorded in the matrix as the debt-made-visible evidence. It goes green only via R2/R3 code.
- **Unit (no Spring), tests first in R2/R5/R6:** use case fakes for every §5.1 branch (all five §5.1.3 rows,
  D19 exhaustion + read-back, PSP-truth conditional update, snapshot content, actor id, requestId propagation);
  validator vectors + byte-sensitivity (wrong key, flipped byte, `1.0` vs `10`, non-canonical order).
- **Full-context MockMvc ITs** (house pattern; no `@WebMvcTest` in Boot 4.1; PG16 Testcontainers; WireMock for
  the outbound PSP only): scenarios 1, 2, 3, 4, 15, 25 (create) and 6, 7, 8, 10 (webhook) + the **full-loop IT**
  (create → hand-signed webhook → `CONFIRMED`, `fee=100`, `net=9900`, outbox row exact, `webhook_events
  PROCESSED`) + cross-tenant 404 + pagination walk. Zero `Thread.sleep`; injected `Clock`/sleeper recorded.
- **Hygiene gates (R7):** `grep -rn "String.format" modules/payments/src/main` (JSON construction) = 0 hits;
  `grep -rn "example.com/callback\|Instant.now()" apps/api modules/payments/src/main` = 0 hits;
  `find . -name "*.disabled" -o -name "*Debug*"` under test trees = 0 hits; no `com.fasterxml.jackson` imports.
- Coverage floors per module maintained (measured post-IT).

## 8. Risks & troubleshooting

| Risk | Likelihood | Mitigation |
|---|---|---|
| "Fixing" the red IT by editing its expectations | High (the exact failure pattern being remediated) | §5.7 rule: the test encodes the spec; change code, or stop and change the spec first — never silently |
| Pressure to re-disable to keep `main` green | Medium | forbidden by new AGENTS §5.5; the red run between R1 and R3 is a documented, bounded state |
| Stale-aggregate fix done cosmetically (reload but still write old fields) | Medium | the IT asserts `expires_at` == PSP value in the DB, not in the response object |
| Retry loop reintroduced around 409 | Medium | 409 `txid_already_exists` → read-back, never retried (E3 §5.7) |
| Importing the simulator's `WebhookSigner` for webhook tests | Medium | test-local signer only; simulator module is out of scope and must not appear in payments' imports |
| Signature vector "almost matches" | High (classic) | UTF-8 explicit, `ts + "." + rawBody` exact bytes, lowercase hex; recompute the vector before blaming code |
| Raw body re-serialized before signing/persisting | High (classic) | capture bytes once in the controller; sign, store, and parse THOSE bytes |
| Ledger edits drift between docs and `docs/epics.md` | High (history: 2×) | §5.6 carries exact row texts; R7 greps the ledger for the new strings before closing |
| Jackson 3 regression | Medium | lesson #13 grep gate; `tools.jackson.*` only |
| Scope creep into E3.5 (protection/backup) or E5 | Medium | scope check before every push: `git diff --stat main -- apps/psp-simulator modules/ledger modules/notifications` = 0 |

## 9. Closure checklist (epic DoD)

- [ ] Defect register §2 zero open: every BD/MS/TD id closed by CI test (name + run id) or §5.6 doc correction
- [ ] `CreatePaymentScenarioIT` runs green in CI (the red run id and the green run id both cited in the matrix)
- [ ] Webhook intake per E4 §5.1–§5.4: validator vectors byte-exact, pipeline order proven, scenarios 6/7/8/10 +
      full-loop IT green in CI
- [ ] Hygiene gates green: no debug/disabled tests, no `String.format` JSON, no hardcoded callback/merchant,
      no `Instant.now()` in request paths, no `com.fasterxml.jackson` in prod sources
- [ ] Matrices: `e3r-acceptance-matrix.md` zero pending; `e3-acceptance-matrix.md` rewritten;
      `e4-acceptance-matrix.md` created — every cell = CI test + run id (runs #12 `33263651319` and #13
      `33267438415` are the only green runs predating this epic; everything cites runs from E3R onward)
- [ ] Ledger per §5.6 applied and raw-verified; README/CHANGELOG truthful (create + webhook work, evidence cited);
      `.env.example` complete; design §8.2 sync note landed
- [ ] AGENTS.md §5.5/§5.6/§7/DEBT-3 + lessons #14 landed
- [ ] `mvn -B verify` green locally; CI green on `main` with the final run id cited; scope diff = 0
- [ ] E5 unblocked (and only now)
