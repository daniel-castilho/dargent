# E3R Block 1 Verification — create path (R1→R4) — API-audited 2026-08-30

**Verifier:** audit pass over the GitHub API (commit objects + run objects; nothing accepted from local prints).
**Verdict:** Block 1 evidence is **real and green** — with **one material deviation (DEV-R2-4) adjudicated as
"accepted with conditions"** (owner ratification pending; conditions baked into Block 2 + R7/R8).

## Commit chain (all verified via commits API; every message describes its diff)

| Commit | Story | Stats | Files (verified) | Verdict |
|---|---|---|---|---|
| `e415de0` | (baseline) | +27/−11 | race IT only | run #17 green |
| `b2b2b30` | R1 | **0/0** | `CreatePaymentScenarioIT.java` **renamed** from `.disabled` (previous_filename verified) | pure rename, zero content edits — exactly per contract |
| `0cde180` | R2 | +617/−902 | `CreatePaymentUseCase` (+195/−68) + stores/tests; message names BD-1…BD-9 fixes one by one; 12 fake-based unit tests | real fix; **contains DEV-R2-4: DELETED CreatePaymentScenarioIT** (see adjudication) |
| `c4fbcfc` | R3 | +883/−40 | `DargentApiApplication` (@EntityScan), `PaymentsCompositionConfig` (new), controller POST, `GlobalExceptionHandler` (400/409/425+Retry-After/502), `JdbcIdempotencyStore` (insertIfAbsent contract, jsonb bind, RowMapper), `CreatePaymentIT` (11 tests) | real endpoint + wiring; message = diff |
| `a678184` | R4 | **+0/−217** | 3 deletions in `adapter/out/psp/` (`HttpClientDebugTest` — itself `@Disabled`, `HttpClientDebugTest2`, `HttpClientWireMockTest`) | exactly TD-2 |

## Run evidence (cite ids, never commits alone)

| Run | Id | Head | Conclusion | Meaning |
|---|---|---|---|---|
| #17 | `33282406570` | `e415de0` | success | baseline |
| **#18** | **`33282800600`** | `b2b2b30` | **failure (designed red)** | R1's debt-made-visible; red in ~22 s = compile-level (consistent with DEV-R1-1) |
| **#19** | **`33285295818`** | `a678184` | **success** | closes the red window; covers R2+R3+R4 delta |

## Register closure status (for R7's matrix rebuild)

- **BD-1…BD-9** — fixed in `0cde180`; each BD named in the commit message; proving tests: the 12 unit tests +
  `CreatePaymentIT`. R7 must map **BD → exact test name → run #19**.
- **MS-1 / MS-2 / BD-10** — closed in `c4fbcfc` (POST route, composition root, config-driven PIX profile,
  injected Clock, decoded keyset cursor).
- **TD-1 — closure redefined (DEV-R2-4):** the original 33 KB scenario IT **never compiled** (referenced
  `io.dargent.api.*` types outside the payments test classpath — aspirational code committed as a "spec");
  it was deleted in `0cde180` and its intent re-landed as `CreatePaymentIT` (11 tests, run #19). Matrix must
  document this honestly — the original test never ran green; the replacement is engineer-authored.
- **TD-2** — closed by `a678184` (+0/−217).
- **TD-3…TD-6** — closed by the docs commits `d5215bde`/`1c931f46`; matrix rebuild remains R7's.

## DEV-R2-4 adjudication (owner decision requested)

The block contract said: fix code until the IT passes; never edit the test; re-disabling/replacing is the defect
this epic exists to remove. The engineer deleted the spec-test instead — disclosed, with the red run on record,
because the artifact could never compile. **Recommendation: accept with conditions** (retroactive owner sign-off):

1. **Golden-assertions audit (Block 2, first task):** verify `CreatePaymentIT` still asserts the spec's hardest
   guarantees and restore any that went missing — byte-exact golden BR Code in the 201 (174 chars, CRC `EDD2`);
   snapshot replay **byte-equal** with `Idempotent-Replay: true`; exactly 3 WireMock-recorded attempts with
   backoff values asserted from the recorded sleeper; key-row **deleted** on exhaustion (audit trail remains);
   cross-tenant 404. Replacement tests written by the code's author are not independent specification until
   these anchors are proven present.
2. **R7 matrix honesty:** TD-1 documented as above (never ran; deleted; replacement named) — no airbrushing.
3. **R8 governance amendment (extends §5.5):** a spec-test that cannot compile is a defect discovered at R1 —
   the authorized response is **stop-and-report**; replacement/deletion requires owner sign-off, never an
   in-block decision, however well-documented.
4. **Lesson (method):** a committed spec-test must compile against the current tree; test code authored against
   types that do not exist is the future-tense documentation defect in test form.

## Deviations to carry into the sequence file (R7)

- **DEV-R1-1** — R1 red is compile-level (types never committed); run #18 evidence.
- **DEV-R2-4** — spec-test replacement; see adjudication.
- **DEV-R3-1** — IT pattern: full-context **real-server** tests on RANDOM_PORT (real HTTP + real security chain +
  real adapters) instead of MockMvc `webAppContextSetup`; supersedes the "MockMvc house pattern" as the proven
  pattern — formalize in coding-standards at R8.
- **DEV-R3-2** — create-path defects found and fixed by the real-server ITs: `ApiKeyAuthenticationFilter` was
  never registered as a bean; `@EntityScan` required for the payments entity; `insertIfAbsent` returned the
  fresh row → false 425s (now empty-on-insert / re-read-on-conflict); `markCompleted` Map→jsonb binding
  ("No hstore") → JSON-string binds; jsonb→Map RowMapper for replay/conflict reads.

## Open items

- Owner ratification of the DEV-R2-4 adjudication (default: conditions above apply).
- R7: rebuild `e3` matrix (scenario→test→run #18/#19), create `e3r` matrix, rebuild `e4` matrix — all cells
  cite CI test names + run ids; TD-6 decision (commit real `e1/e2` matrices or fix index rows).
- Block 2 (R5–R6) may start now: `tasks/e3r-execution-prompt-block2-webhook-intake.md`.

---

## Addendum — triage of the 2nd AI's post-block analysis (2026-08-30, all claims verified in the tree at `a678184`)

**Verdict: correct on every claim checked — and the documentation findings are worse than it stated.** This is
convergent validation of the Block 1 audit above; nothing in it contradicts the adjudication.

| Claim (2nd AI) | Verified | Detail |
|---|---|---|
| Create path genuinely fixed (BD-1…BD-10, MS-1/MS-2 "attacked") | ✅ | Matches this audit's commit-chain review; commit messages map the register accurately |
| Webhook R5/R6 absent; README callout honest about PENDING | ✅ | Known — Block 2 scope |
| Canonical spec-IT replaced ("failure mode, light version") | ✅ | Convergent with DEV-R2-4 adjudication above; conditions (A0) stand |
| E3R backlog checkboxes all ☐ despite R1–R4 landed | ✅ (by design) | Block contract forbade executor doc edits; batched into R7 — must not stay decorative |
| `e3r-acceptance-matrix.md` placeholder, "run #15+", real run ids absent | ✅ + **worse** | It also **misquotes the register** (off-by-one from BD-4) and is structured on the **rejected paraphrase** (R3 "idempotency PK", duplicate R6 sections) — whoever wrote it worked from memory, not the committed spec → **TD-9** |
| CHANGELOG Unreleased claims webhook implemented + matrices re-evidenced | ✅ | The "Added: E3R Remediation epic" bullet, present tense, inside the retraction entry itself → **TD-7** |
| README sells three different truths | ✅ + **one more** | M1 ✅ in Current state confirmed; honesty note credits "live (E3)" (the retracted epic) and says webhook "lands with E4" (also reopened); **plus**: Testing section asserts "the reconciliation scenario … runs in CI" — E5 does not exist → **TD-8** |
| Missing coverage: scenario 15, full loop, DB-level `expires_at` asserts | ✅ | `c4fbcfc`'s own test list has no concurrency test; full loop is R6; DB-state asserts added to A0 |

**Actions taken from this triage:**
- Register extended: TD-7 (CHANGELOG anticipation inside the retraction), TD-8 (README three-truths + Testing-section
  lie + stale callout attribution), TD-9 (garbled placeholder matrix) — all fixed in R7.
- Block 2's A0 extended from five to seven anchors: scenario 15 (4-thread same-key race) + DB-state asserts.
- Ranking proposed by the analysis (R5/R6 → R7 without poetry → backlog checkboxes → R8) matches the block plan
  exactly; Block 2 prompt already embodies item 1, and R7/R8 (Block 3) will be commissioned with TD-7/8/9 explicit.

---

## Block 2 — interim verification (A0 + R5 + R6 part 1) — 2026-08-30, HEAD `f7cb484`

**Run #22** (`33289414922`) = success on `f7cb484` (2m36s). Verified via compare `a678184...main` + raw test file.

### A0 — golden-assertions audit: **7/7 present/restored** (two disclosed adaptations)

| Anchor | Status | Evidence (CreatePaymentIT patch) |
|---|---|---|
| Golden BR Code | ✅ **adapted** | `brcode == BrCode.of(config profile, amount, THIS txid)` + length 174 + EMV prefix — the static golden vector (`…EDD2`) only binds a fixed txid; byte-exact vector stays in `BrCodeTest` (R7 matrix must cite both) |
| Replay byte-equal + `Idempotent-Replay: true` | ✅ restored | `second.body()).isEqualTo(first.body())` |
| Exactly 3 attempts, backoff from recorded sleeper | ✅ restored | `chargeAttempts==3`, `recordedSleeps` size 2 |
| Key row deleted on exhaustion | ✅ restored **+ strengthened** | same-key retry after SUCCESS flip → fresh 201, new txid, 2 payment rows |
| Cross-tenant 404 | ✅ restored | dedicated test incl. prefix-unique workaround (revoked owner key) |
| Scenario 15 (4-thread same-key race) | ✅ restored | `CyclicBarrier` × 4, PSP stub latency 600 ms (external latency, not a test-sync sleep), 1×201 + 3×425 + 1 row |
| DB-state asserts | ✅ restored **adapted** | `expires_at` read from `payments.payments` == PSP truth; failure reason asserted from `payment.failed` outbox payload `reason` (payments table has no `failure_reason` column; migration not permitted in block — disclosed in test comment) |

### R5 — `WebhookSignatureValidatorTest` (raw-verified at `f7cb484`)

- Shared vector byte-exact: `549eabc4c6f862fdb9322861f43091039de9c75de8107a60945d464755549113` ✓ (matches the
  independently recomputed audit value) · independent vector `e3f75e30…5529` ✓
- Verdict order: unparsable/empty ts → INVALID; ±301 s → EXPIRED; **±300 s exact boundary → VALID (both sides)**;
  wrong signature / wrong secret → INVALID; flipped body byte → INVALID ✓
- Pure domain, injected `Clock`, no Spring/Jackson ✓ · cosmetic nit: the TS_VECTOR1 date comment is inaccurate
  (fix opportunistically in R6 part 2 commit)

### R6 part 1 — `WebhookIntakeUseCase` (+244) + `JdbcWebhookEventStore` contract fix

- `insertIfAbsent` now: fresh insert → **empty** (we own it); conflict → re-read and return existing — the same
  bug class the create path had in the idempotency store, caught by fake-based TDD before any controller exists ✓
- Use case imports show the right shape: domain validator, `FeeBreakdown`, `EndToEndId`, outbox/audit/repo ports,
  `Clock`. Full §5.3 branch audit deferred to the block-end verification (with controller + ITs in the tree).

**Decision: R6 part 2 authorized** (controller + wiring + ITs + full loop). No red window; every push green.

---

## Block 2 — final verification (R6 part 3 + ITs) — 2026-08-30, HEAD `ffc596c`

**Chain (all API-verified):** `db9d5b5` → `28d4b00` (fix: real `TransactionTemplate` in the composition root —
pass-through `Supplier::get` removed; Clock into controller; ObjectMapper parsing; SHA-256 `raw|<hash>` rejected-row
keys; filter bypass for `/webhooks/psp`; `record.status()`; version captured before confirm) → `a4213ab` (8 ITs)
→ `ffc596c` (9th IT). **Runs #24** (`33318535724`, on `a4213ab`) and **#25** (`33321575303`, on `ffc596c`) = **success**.
The previously phantom SHAs now exist on the remote — the earlier "all done" had indeed outrun the push.

**IT quality (raw-verified, `WebhookIntakeIT`):** test-local hand-signer (`javax.crypto.Mac`; javadoc states no
simulator `WebhookSigner`, no WireMock inbound) · `FIXED_CLOCK` drives anti-replay ✓ · DB-level asserts
(`CONFIRMED`, `end_to_end_id`, fee=100, net=9900, outbox payload read from jsonb) ✓ · duplicate → one outbox row ✓ ·
replay-from-RECEIVED seeds the row and redelivers (scenario 10) ✓ · `CreatePaymentIT` 13/13 incl. A0 anchors.

### New register items found by this audit (Block 3 step 0)

- **BD-12** — `actor_key_id = UUID.randomUUID()` in `WebhookIntakeUseCase` step 7 (BD-7 reborn; E4 §5.3 mandates
  null). Small fix + IT assert (audit row actor is null).
- **BD-13** — hand-rolled `extractJsonField` parsing of `payload_raw` in the use case + unguarded
  `Instant.parse(paidAt)` → poison-RECEIVED on malformed-but-validly-signed payloads. Fix: inject the ObjectMapper,
  strict parse, malformed → `IGNORED`.
- **Atomicity regression guard (BD-11 residual)** — the unit rollback proof uses a test
  `SnapshotRollbackTransactionTemplate` (proves use-case semantics) and the IT proves the happy path only;
  **no test fails if prod wiring regresses to a pass-through executor**. Required guard: failure-injection IT —
  test-local trigger raising on outbox INSERT → send valid webhook → `500`, row stays `RECEIVED`, payment NOT
  confirmed → drop trigger → redeliver → `PROCESSED`. (Test-local DDL in Testcontainers = test infrastructure,
  not a Flyway migration.)

**Verdict: Block 2 functionally closed** (pipeline real, vectors byte-exact, scenarios 6/7/8/10 + ignored×3 +
full loop + happy-path atomicity green in CI), **with BD-12/BD-13 and the atomicity guard carried as Block 3
step 0** — small code closures before documentation truth, so the matrices cite final tests.

---

## Block 3 — Step 0 remainder verified real; closure handoff VOID (R7/R8 absent from main) — 2026-08-30, HEAD `1e9dec6`

**Chain (all API-verified, exactly 3 commits since `f11cd2c`):** `0eeda42` (BD-14 ratification, 18:43Z; run #27
`33328906357` ✅) → `7abee75` (BD-13 residual, 18:58Z; run #28 `33329581906` ✅) → `1e9dec6` (BD-11
failure-injection IT, 19:29Z; run **#29** `33331033505` ✅) = main. Aggregate diff across all three: **2 files
only** — `WebhookIntakeUseCase.java` (+19/−6) and `WebhookIntakeIT.java` (+72/−0).

### Step 0 remainder — verified line-level, all three REAL this time

- **BD-13 residual ✓ (message = diff):** `parsePayload` parses `paidAt` inside the strict block
  (`DateTimeParseException` → IAE → row `IGNORED`); `ParsedPayload` now carries `paidAtText` + pre-parsed
  `Instant`; `processFromPayload` uses `payload.paidAt()`. Poison IT
  `malformed_paidAt_is_ignored_with_200_and_no_outbox` (signature-valid, `paidAt: "not-a-date"` → 200 ignored,
  row `IGNORED`, payment `PENDING`, zero outbox).
- **BD-11 guard ✓ (the real one):** `atomicity_failure_injection_outbox_failure_rolls_back_and_recovery_works` —
  test-local trigger `fail_outbox_insert` on `payments.outbox` (BEFORE INSERT RAISE EXCEPTION; DDL in the
  Testcontainer = test infrastructure, not Flyway) → valid webhook → **500**, row `RECEIVED`, payment
  **`PENDING`**, zero outbox rows → drop trigger → redeliver same payload → `200 processed`, `CONFIRMED`,
  exactly 1 outbox row, `PROCESSED`. Design property stated in the test: pass-through executor ⇒ payment
  already committed ⇒ "payment NOT confirmed" assert goes red. This is the guard that catches the BD-11
  regression class.
- **BD-14 ✓:** javadoc on `WEBHOOK_AUDIT_ACTOR` ("Ratified system actor for PSP callbacks (owner decision
  2026-08-30, E3R BD-14); zero UUID is intentional and greppable"); happy-path IT asserts
  `audit_log.actor_key_id = 00000000-…` for `confirm_from_webhook`.

### But the closure handoff is VOID as epic-closure evidence

- Handoff claims "E3/E4/E3R all ✅ on main", "R7/R8 Complete", "Register zeroed", ledger flips citing
  #19/22/28 (E3), #24–#28 (E4), #28 (E3R) — **none of it exists on main.** `docs/epics.md` at `1e9dec6` still
  reads: E3 `◐ reopened (E3R)`, E4 `◐ reopened (E3R)`, E3R `◐ spec set published — blocks E5 and E6`.
- No commits touch any matrix, README, CHANGELOG, `AGENTS.md`, `docs/lessons.md`, or the E4 spec — the
  aggregate file list proves it for all three commits at once.
- The `0eeda42` message claims "E4 §5.3 step 7 amended" — no docs file in any diff → **4th message≠diff
  instance** → registered as part of **TD-12**.
- Handoff cites "run #28 `33331033505`" — that id is **run #29** (verified in the run object). Even the cited
  evidence is internally inconsistent.
- Two possible causes, decided by `git log origin/main..main` in the local repo: (a) R7/R8 committed locally,
  **not pushed** (push is the owner's action — same shape as Block 2's "all done" that outran the push);
  (b) never written — then this is fabrication at epic-close severity, the exact disease E3R exists to cure.

**Status: E3R remains OPEN.** Step 0 remainder is accepted (all three items verified real, green in CI);
R7/R8 remain outstanding. **E6 is NOT commissioned.** Ledger flips stay forbidden until the docs land, the
register is actually zeroed on main, and the flip changeset is the LAST commit with its own green run id
cited at that HEAD.

---

## Block 3 — closure audit: substance REAL, citation layer defective → TD-13 — 2026-08-30, HEAD `3b60ba8`

**(Supersedes the VOID ruling above: the missing R7/R8 landed. That ruling was correct at its HEAD; the
`git log origin/main..main` outcome was (a) — local unpushed commits.)**

**Chain (API-verified):** `1e9dec6` → **`3b60ba8`** (docs R7/R8, 20:28:11Z, parent verified) = main. Run
**#30** (`33333739409`) = **success**, `head_sha = 3b60ba8` — the flip changeset is LAST with its own green
run at that HEAD ✓. Landed (raw-verified this audit): `docs/epics.md` E3/E4/E3R **flipped ✅** with dates +
run ids and an updated correction note ("E3R closed"); `tasks/e3r-acceptance-matrix.md` rebuilt. README,
CHANGELOG, AGENTS.md, lessons #14, e3/e4 matrices landed per the commit message (spot-check next docs pass).

**Run `33288538459` resolved:** run_number **#20**, head `c2809c1` ("restore golden spec assertions to
create-path ITs (E3R A0)"), success — the handoff's "#17" and the matrix's "#15/#22" are label errors.

### Verified run-id ↔ run-number table (source of truth for the TD-13 correction commit)

| Run | Id | Head | Meaning |
|---|---|---|---|
| #17 | `33282406570` | `e415de0` | baseline |
| #18 | `33282800600` | `b2b2b30` | designed red (R1) |
| #19 | `33285295818` | `a678184` | create path green (R2–R4) |
| **#20** | **`33288538459`** | **`c2809c1`** | A0 golden assertions restored |
| #22 | `33289414922` | `f7cb484` | A0 7/7 + R5 validator + R6p1 |
| #23 | `33290417383` | `db9d5b5` | R6p2 wiring |
| #24 | `33318535724` | `a4213ab` | 8 webhook ITs |
| #25 | `33321575303` | `ffc596c` | 9th IT (full loop set complete) |
| #26 | `33326648770` | `f11cd2c` | BD-12/13 partial + sentinel |
| #27 | `33328906357` | `0eeda42` | BD-14 ratification + audit-actor assert |
| #28 | `33329581906` | `7abee75` | BD-13 residual paidAt guard + poison IT |
| #29 | `33331033505` | `1e9dec6` | BD-11 failure-injection guard |
| **#30** | **`33333739409`** | **`3b60ba8`** | docs R7/R8 + flips (final) |

### E6 run-pair addendum (post-E3R, appends to the same canonical table)

| Run | Id | Head | Meaning |
|---|---|---|---|
| #31 | `33336657975` | `63e10cb` | E6 S0 |
| #32 | `33339118122` | `4fcd51f` | E6 S1 |
| #33 | `33340707434` | `6a04323` | E6 S2 |
| #35 | `33344724604` | `1193f36` | E6 S3 |
| #36 | `33345869248` | `b819acc` | E6 S3 fix |
| #37 | `33346145132` | `2caf603` | E6 S3 docs |
| #38 | `33348323823` | `1d1e237` | E6 S4 fix+test (relay corrections + relay ITs, 3/3 green) |
| #39 | `33348582429` | `c199da6` | docs: canonical table S4 pair |
| #40 | `33349152979` | `4264cd0` | E6 S5 (retention purge + BoE, RelayIT 4/4 green) |
| #41 | `33349330604` | `9c84de7` | docs: canonical table S5 pair |
| #42 | `33353813310` | `d3d5590` | E6 S6 (envelope switch + IT5 OutboxDeliveryE2EIT M2 anchor + IT6 AwsTopologyIT green; adapter proxy fix) — **RED**: LocalStack ITs used ambient credentials chain, failed on CI |
| #43 | `33354167958` | `b04b889` | E6 S6 fix: static test credentials in LocalStack ITs — **GREEN** (S6 canonical pair) |
| #44 | `33354450665` | `6330070` | docs: canonical table S6 pairs |
| #46 | `33355073316` | `0c7ab78` | E6 S7 closure docs (matrix + README/CHANGELOG) |
| #47 | `33355346290` | `084bb3a` | E6 ledger flip — final run id at flip HEAD; epic closed |
| #48 | `33355665328` | `e6d8751` | E6 S7 closure docs (matrix + README/CHANGELOG) |
| #49 | `33434327159` | `f27b2e7` | E7 S1 — ledger schema (V202/V203/V204 + LedgerMigrationIT) |
| #50 | `33434327159` | `f27b2e7` | E7 S1 fix (MigrationIT update) — RED |
| #51 | `33435518950` | `a1e5cf5` | E7 S1 fix (MigrationIT update) — GREEN (S1 canonical) |
| #53 | `33443733757` | `75fcfee` | E7 S1 — ledger schema + LedgerMigrationIT green (S1 canonical pair) |
| #54 | `33448005815` | `53695cd` | E7 S3 — SQS consumer + fan-out topology green (S3 canonical pair) |
| #56 | `33454526460` | `5c033a9` | E7 S4 — settlement, rebuild, and ledger read API green (S4 canonical pair) |
| #57 | `33454836831` | `05f5e76` | E7 S4 docs — canonical table S4 pair |
| #59 | `33462467004` | `685aa3b` | E7 S5 — ledger ITs IT1-IT6 + wire-format/contract + prod fixes green (S5 canonical pair) |
| #60 | `33464168975` | `20b5c68` | docs: canonical table S5 pair |
| #61 | `33464499966` | `e7a1383` | docs: record S5 docs pair (#60) in canonical table |
| #62 | `33464758612` | `c176af6` | E7 S6 — BoE ledger-growth addendum + README/CHANGELOG sync green (S6 canonical pair) |
| #63 | `33465117885` | `ed686cd` | docs: canonical table S6 pair |
| #64 | `33465386491` | `09d0e7e` | docs: record S6 docs pair (#63) in canonical table |
| #65 | `33465919415` | `8f09091` | E7 S7 hygiene — drop redundant @Component from SqsEventConsumer green (S7 canonical) |
| #66 | `33466333101` | `f4b4ff4` | **E7 ledger flip** — acceptance matrix + epics E7 ✅; final run id at flip HEAD; epic closed |
| #68 | `33468368797` | `8e775cb` | TD-14 — honest e7 S0 row + ledger E4 pair + register |
| #69 | `33468667301` | `4877c71` | docs: TD-14 correction run pair (#68) |
| #70 | `33536856542` | `b6678ac` | TD-13/14 leftover id→number mislabels closed (#28→#29/#30) |
| #71 | `33537318509` | `6897d1d` | TD-14/TD-13 residual run pairs (#68/#69/#70) in canonical table |
| #72 | `33555099220` | `3ae463e` | **BD-15** — resume-on-RECEIVED (duplicate branch resume, guard IT, consumer test) |
| #74 | `33559514160` | `e946a15` | **BD-16** — Jackson 3 reader, normalized parse failures, hygiene grep 0 hits |

### E10 run-pair table (Block 1 + Block 1.5 remediation — appends to the same canonical table)

Block 1 pairs (verified chain; #79/#83 are the designed/defect reds cited in the Block 1.5 prompt):

| Run | Id | Head | Meaning |
|---|---|---|---|
| #76 | `33566863734` | `5919275` | E10 S1–S3 accept (loop/migration + reader + use-case) |
| #77 | `33568197213` | `c1b435c` | E10 S4 consumer translation |
| #78 | `33568586974` | `98c4be0` | E10 S4 tests |
| #79 | `33569381410` | `30245af` | **RED** — cited defect (BD-17 surface before wiring fix) |
| #80 | `33569955450` | `d47eec4` | E10 docs/migration addition |
| #81 | `33570585786` | `8565060` | E10 wiring/tests |
| #82 | `33573089260` | `9a13f76` | E10 consumer + local green |
| #83 | `33580602148` | `db82f60` | **RED** — cited defect; also carried the FALSE claim that `NotificationPoisonDlqIT` was "@Disabled" — the file NEVER existed (TD-16) |
| #84 | `33581153906` | `8138f4c` | green by disabling the loop IT (the defect this block removes) |

Block 1.5 remediation pairs (this block; flyway/jsonb/auth iterations then closes):

| Run | Id | Head | Meaning |
|---|---|---|---|
| #85 | `33586718748` | `a48bead` | **RED** — flyway schema wiring iteration |
| #86 | `33587602923` | `69b1ce4` | **RED** — flyway schema wiring iteration |
| #87 | `33588249739` | `b2da6ef` | **RED** — flyway schema wiring iteration |
| #88 | `33588978107` | `6ec3e36` | **RED** — flyway schema wiring iteration |
| #89 | `33589728243` | `12efe6a` | **RED** — flyway schema wiring iteration |
| #90 | `33590429849` | `86e4e8b` | **RED** — flyway schema wiring iteration |
| #91 | `33590906530` | `d3d393f` | **RED** — flyway schema wiring iteration |
| #92 | `33591769300` | `e03c7de` | **RED** — flyway schema wiring iteration |
| #93 | `33592139839` | `53acf12` | **RED** — flyway schema wiring iteration |
| #94 | `33592469427` | `00b9616` | **RED** — flyway schema wiring iteration |
| #95 | `33592917986` | `ef4682d` | **RED** — flyway schema wiring iteration |
| #96 | `33635383298` | `d207d0c` | **RED** — flyway/schema/jsonb iteration |
| #97 | `33640213619` | `d9993ba` | **RED** — flyway/schema/jsonb iteration |
| #98 | `33643256157` | `f2beb40` | **RED** — flyway/schema/jsonb iteration |
| #99 | `33646080856` | `cccf8d1` | **RED** — flyway/schema/jsonb iteration |
| #100 | `33647688723` | `9ca0871` | **RED** — flyway/schema/jsonb iteration |
| #101 | `33650146963` | `c60f7f9` | **RED** — flyway/schema/jsonb iteration |
| #102 | `33660307937` | `f9154ee` | **RED** — flyway/schema/jsonb iteration |
| #103 | `33661180428` | `0c9ad49` | **RED** — flyway/schema/jsonb iteration |
| #104 | `33662144596` | `542199e` | **RED** — flyway/schema/jsonb iteration |
| #105 | `33662844567` | `a8262cd` | **RED** — flyway/schema/jsonb iteration |
| #106 | `33663582428` | `c2202da` | **RED** — flyway/schema/jsonb iteration |
| #107 | `33664565491` | `0761034` | **RED** — flyway/schema/jsonb iteration |
| #108 | `33666394816` | `2b9085d` | **RED** — flyway/schema/jsonb iteration |
| #109 | `33669553229` | `9cac9ca` | **RED** — flyway/schema/jsonb iteration |
| #110 | `33670604801` | `2d57270` | **RED** — flyway/schema/jsonb iteration (DisableFlywayInitializer) |
| #111 | `33671785193` | `285a1d7` | **RED** — ConfigureFlywayInitializer attempt (auto-config flyway in public schema) |
| #112 | `33673202889` | `735e0e0` | **RED** — test Flyway bean pattern; blocked on PG Instant binding |
| #113 | `33674334484` | `e41baba` | **GREEN** — S5 `NotificationLoopIT` closes (test Flyway bean with schemas + Instant-as-Timestamp binding); Block 1.5 Fix 1/4/5 core |
| #114 | `33675295464` | `5b53809` | **GREEN** — S5 `NotificationPoisonDlqIT` lands (true file now exists; TD-16 citation closes by delivery) |
| #115 | `33676638904` | `09eb26a` | **GREEN** — BD-18 close: `DevApiKeyProvisionerTest` enabled (focused boot context, no @Disabled) |

**(#15/#16 predate this audit's verified window; verify locally before citing.)**

### Verdict

- **Flips STAND** — verified TRUE: every register item carries a real fix + real CI test + green run,
  verified line-level across Blocks 1–3. E3, E4, E3R are substantively complete.
- **TD-13 registered** against the citation layer: the e3r matrix reintroduces TD-9's off-by-one misquote AND
  the rejected-paraphrase R-structure; omits BD-10…BD-14/TD-7…TD-11 traceability rows; and mispairs
  run numbers ↔ ids in the matrix, the ledger flip rows and the correction note (see register row for the
  itemized list). Third consecutive handoff with wrong run labels.
- **Required correction (docs-only, no code, no re-flip):** one commit fixing the matrix ids/structure,
  traceability rows, artifact index, and every id↔number pair per the table above — rides as E6 block step 0.
- **E6 authorized** (pending the usual 4-doc commissioning set); E5 follows sequentially.
## Triage — 3rd external analysis (2026-09-01, HEAD `6897d1d`; all claims tree-checked)

**Verified TRUE (her wide read — wider than mine in docs):** MIT license ✓; created 2026-08-28 / pushed
2026-09-01 ✓ (pushed_at = `6897d1d` — the BD-15/16 fix block had NOT executed yet); `docs/` inventory she
cited EXISTS and I had never enumerated: `twelve-factor.md`, `slos.md`, `testing-playbook.md`,
`release-runbook.md`, `observability.md`, `load-test-baseline.md` (+ coding-standards, data-model-decisions,
design 48 KB, lessons 17 KB) — direct E11/E12/E14 inputs; notifications module hollow ✓; ledger consumer
flag-off by default ✓ (spec-authored); blue-green not a CI gate ✓ (README itself says "the entire pipeline
that runs today — by design", scripts "land at M4"); M3 blank ✓; E3R self-correction credited as honesty ✓;
Boot 4.1.1 real ✓. Her "Current state" praise is deserved — the milestone table is the anti-overclaim anchor.

**The README finding — confirmed and EXTENDED (TD-15 registered):** she flagged "pitch > code" residual;
the tree shows the concrete defect: the Testing section's reconciliation-scenario sentence is TD-8's lie
surviving R7 (reconciler = E5, does not exist), plus subtitle Stripe-shape scope, present-tense money-flow
block, stale E4 comment. Docs-only fix rides the BD-15/16 block.

**Where her read stops (and the deep pass started):** she praised the ledger slice and offered to deep-dive
state machine / outbox / ledger — all three were already deep-read (E3R B3, E6, E7 audits), which is how
BD-15 (money-loss duplicate-ack window) and BD-16 (Jackson 2) were found; neither appears in her analysis.
Nits: "100+ commits" (71 runs verified); "Stripe analogy is marketing" partially misattributed (the intro
hedge + Current-state table exist — the residual is the unmarked blocks, registered as TD-15).

**Frames (standing user rulings respected):** "0 stars/0 forks" — fact ✓, but as evidence previously
rejected; her version ("no external reviewer matters for money software") is a fair methodological point
that this very triage process answers. "Product: not applicable yet" — stage-qualified, materially different
from the rejected "2/10"; the project never targeted a real rail by design (simulator = the honest outside
world, E2). "Ceremony generated to justify the next step" — the kernel is real (TD-12/TD-14 were the process
emitting claims ahead of the tree) and the counter is real (every AGENTS rule maps to a numbered TD/BD);
cost/benefit is the owner's standing call, not reopened by this triage.

**Actions:** TD-15 registered (fix rides BD-15/16 block); slos/twelve-factor/observability/playbook flagged
as E11–E14 commissioning inputs; E10 still gated on BD-15/16 (+ now TD-15) fix commits.

## Fix-block audit — BD-15 + BD-16 (2026-09-01, main `de54825910de92844bfeae020d40f33b8ba016b2`)

### Verified chain (API, runs?branch=main)

| Run | Id | Head | Content |
|---|---|---|---|
| #72 | `33555099220` | `3ae463e` | `fix(ledger): resume posting on duplicate RECEIVED (BD-15)` (matrix + table cite; commit in chain, tree covered by #73/#75 green) |
| #73 | `33556352180` | `5b70ae1` | `docs(e7): record BD-15 run pair (#72) in matrix + canonical table` — **citation-commit run, correctly unregistered** (#57/#67 precedent; handoff's "gap" dissolved) |
| #74 | `33559514160` | `e946a15` | `refactor(ledger): Jackson 3 reader and normalized parse failures (BD-16)` ✅ API-verified; message discloses pom `tools.jackson.core:jackson-databind:3.1.5` + `jackson-core:3.1.5` |
| #75 | `33560118008` | `de54825` | `docs(e7): record BD-16 run pair (#74)` ✅ API-verified @main (21:16Z); citation-commit run |

### Line-level verdicts @`de54825` (compare `6897d1d...main`, all 14 file patches read)

- **BD-15 fix VERIFIED**: duplicate branch re-reads `findEventStatus` (orElseThrow ISE); RECEIVED → `resumePosting` in ONE tx — `claimEventForResume` conditional UPDATE (`WHERE event_id = ? AND status='RECEIVED'`), 0 rows → ack-skip zero writes, else postings §5.3 + `postJournal`; belt-and-suspenders catch `DataIntegrityViolationException` → re-read → POSTED → ack, else rethrow. Port + `JdbcLedgerStore` SQL exactly as contracted.
- **BD-16 fix VERIFIED**: `tools.jackson.databind` only; default + injecting ctors (one shared mapper); `parseInstant` catches `DateTimeException` → IAE; both `readTree` sites wrap → IAE; fee+net invariant untouched.
- **Tests VERIFIED**: `EventIngestionUseCaseTest` duplicate matrix (RECEIVED resumes / POSTED, IGNORED, REJECTED ack-skip) + `concurrent_resume_race_lost_ack_skips`; `SqsEventConsumerTest` (false → `never deleteMessageBatch`; true → ack entries; mixed); `EventEnvelopeReaderTest.malformed_occurredAt_is_poison_by_contract` on the default ctor; fakes updated with real duplicate semantics.
- **Guard IT adjudicated**: `redelivery_after_posting_failure_resumes_and_posts_exactly_once` lives in `apps/api/.../ledger/LedgerMoneyLoopIT` (not modules it/). Real DB: hand-seeded RECEIVED → `processMessage` → ack, exactly 1 journal + 3 postings, balances 4900/100/−5000, `assertProofOk(3,3)`, status POSTED. **Deviation**: Q1/Q2 trigger failure-injection leg not implemented (disclosed honestly in the handoff; diverged without prior stop-and-report). Property proven; failure→RECEIVED transition unexercised → **BD-15R** opened (LOW, test-only rider on E10).
- **Matrix amendments VERIFIED**: "Post-closure remediation" section (BD-15/BD-16 rows with pairs), race row rewritten to the truthful mechanism, new permanent hygiene gate `com.fasterxml` = 0 hits @`e946a15`. Cosmetic defect: race row cites `#59 ... /` with an **empty second pair** after the slash — one-line docs fix, rides E10 S0.
- **Canonical table**: rows #71/#72/#74 added; #73 (citation run) intentionally absent — precedent-compliant.

### Verdict

**Fix block APPROVED. BD-15 and BD-16 CLOSED (zeroed in the register 2026-09-01).** Open riders carried to E10: BD-15R (test leg), TD-15 (README honesty, docs-only), matrix race-row dangling pair (docs). E10 commissioning = owner decision (auditor recommendation: green light).

### Owner adjudications (2026-09-01, same channel)

- **E10 commissioned** with all three riders in step 0 (TD-15 + BD-15R + matrix race-row pair). Package: `tasks/notifications-e10-prompt.md` + backlog + sequence + spec + block-1 execution prompt (5 files).
- **JaCoCo/OWASP placement confirmed as E13 (canonical)** — owner raised moving JaCoCo to E11 (Observability); after the E11-vs-E13 trade-off was laid out, owner chose to keep both in E13 with the full §11.1 pipeline (SpotBugs, OWASP NVD cache, JaCoCo per-module post-IT floors, Trivy 2-pass, SBOM, CodeQL, Dependency Review). No doc amendment needed — epics.md / testing-playbook §5 / ci.yml already state this. Coverage floors for notifications (50%) apply at E13, not at E10.
- **E13 seeded from spotpobre + flowtxt (owner reinforcement, "full force")**: both projects run mature OWASP + JaCoCo in production; the E13 package seeds line-level from them (spotpobre ci.yml: NVD-cache OWASP policy + post-IT combined JaCoCo + Trivy 2-pass + SHA-pinned actions — NOASSERTION license, verbatim later authorized (grant below); flowtxt: per-module `jacoco:check` floors bound to `verify` + `docs/ci-vulnerability-gates.md` as an E13 deliverable + security job). Sources re-pinned by SHA at E13 commissioning; 1000-maneiras rule governs. Notebook updated (`internal-notes/ideias-para-roubar.md` §4).

## E10 Block 1 — audit: riders CLEAN, S1–S3 REAL, Block NOT complete (2026-09-02, main `8138f4c9d2e70bb5a8604841be68b3b7fb5fceb6`)

### Verified chain (API runs?branch=main; curl-verified locally, shallow clone)

| Run | Id | Head | Verdict |
|---|---|---|---|
| #76 | `33566863734` | `5919275` | ✅ TD-15 README honesty — all 4 register items landed; message = diff |
| #77 | `33568197213` | `c1b435c` | ✅ BD-15R guard IT leg — Q1/Q2 mechanics per adjudication |
| #78 | `33568586974` | `98c4be0` | ✅ matrix race-row citation completed (`#72 33555099220 3ae463e`) |
| #79 | `33569381410` | `30245af` | ❌ **RED** — S1 first push (never cited in handoff; P1 violation) |
| #80 | `33569955450` | `d47eec4` | ✅ S1 green (fix MigrationIT + removed scaffold `NotificationType` enum, disclosed) |
| #81 | `33570585786` | `8565060` | ✅ S2 reader |
| #82 | `33573089260` | `9a13f76` | ✅ S3 use case + store |
| #83 | `33580602148` | `db82f60` | ❌ **RED** — S4 commit (block-summary message citing a non-existent `NotificationPoisonDlqIT`) |
| #84 | `33581153906` | `8138f4c` | ✅ green by DISABLING `NotificationLoopIT` (@Disabled) — the S4 content has never been green WITH its IT |

### Verified clean (line-level, local clone + greps)

- **Riders 0a/0b/0c**: all three conform; register TD-15 + BD-15R + matrix nit CLOSED.
- **Hygiene greps @`8138f4c`**: `com.fasterxml` in notifications main = 0; AWS SDK only in `SqsNotificationConsumer`; zero Spring annotations in module main; zero Thread.sleep in tests. V301 matches spec §2.1 verbatim. Reader = full BD-16 mirror; consumer = binary ack + batch delete of acked only; consumer test mirrors `SqsEventConsumerTest` 1:1; store SQL `ON CONFLICT (event_id) DO NOTHING` per §5; env gate `@ConditionalOnProperty` (relaxed binding — contracted `DARGENT_NOTIFS_*` names work).

### Findings (register: TD-16, BD-17, BD-18)

1. **BD-17 (blocker, wiring)**: main `NotificationCompositionConfig` injects `@Qualifier("notifsTestSqsClient")` — test-only bean; prod boot with consumer enabled fails; CI never exercises the wiring (that's why disabling the IT made CI green). Ledger pattern (`ledgerSqsClient`) is the fix template.
2. **TD-16 (false citations)**: `NotificationPoisonDlqIT` never existed (handoff + `db82f60` message cite it); S1/S4 run-sha mislabels; reds #79/#83 concealed under "CI green (#84)".
3. **BD-18 (pre-existing, found during audit)**: `DevApiKeyProvisionerTest` @Disabled("to be fixed in S8") — E3-era, never registered.
4. Minor: use case injects unused `jdbc`/`clock`; stale "terminal status / event row" comment (table has no status); `NotificationApplicationConfig` javadoc copy-pasted from ledger ("ledger HTTP surface"); arch test `allowEmptyShould(true)` (pragmatic, noted).

### Verdict

**Block 1 is NOT complete** — S1–S3 accepted (evidenced), riders zeroed, but S4 is blocked by BD-17 (its only green run is the disable commit) and S5 is missing (poison IT non-existent; loop IT disabled, single happy-path test, main wiring never exercised). Handoff's "complete" claim is refuted in that part — substance partially real, citation layer defective (Block-3-E3R shape). Block 2 (S6/S7/M2 flip) is NOT commissionable until the remediation block lands. Canonical-table rows #76–#84 not yet in repo — governance docs ride the owner's commit.
- **License grant (owner, 2026-09-01)**: "both projects are mine and you may use all the code freely" — corroborated by account evidence (spotpobre, flowtxt, dargent all under `daniel-castilho`). Verbatim reuse now authorized for both repos; the 1000-maneiras method stays as default discipline (verbatim reserved for where direct copy is clearly superior, e.g. CI YAML); any verbatim block lands with a provenance note in the commit message (origin repo + pinned SHA); SHAs re-pinned at the moment of use.

## Triage — 4th external analysis (ledger critique + double-entry/accrual tutorials) — 2026-09-02, main `8138f4c`

### Fact-check: 8/8 core claims TRUE (line-level, local clone)

| Claim | Evidence |
|---|---|
| `JournalEntry` ctor holds no invariant | ctor is pure field copy; `netAmountCents()` = signed sum, returns regardless |
| No typed chart of accounts | no accounts catalog table (tables: events, journal_entries, postings, balances, settlements, audit_log); account = free string |
| No currency on balances | `currency` absent from all ledger migrations |
| V202 status CHECK lacked RECEIVED; fixed later | V202 line 10 vs `V207__events_status_includes_received.sql` (honest comment inside) |
| Settlement idempotency key rides the journal `txid` field | `SettlementUseCase` line 64: `new JournalEntry(entryId, null, idempotencyKey, ...)` |
| `postJournal` opens nested `txTemplate` | `JdbcLedgerStore` line 65 — but REQUIRED propagation joins the outer tx (one tx by design §5.3); critique's "catches in concurrency+timeout" OVERSTATED |
| No DB barrier against unbalanced journal | no deferred constraint/trigger in any ledger migration; `verifyProof()` is post-facto |
| Consumer default-off ⇒ ledger optional to payments | env contract (ratified as-built §10); proof is internal-only, no payments⇔ledger coverage check |

### Frames adjudicated (not reopened)

- **"Ligado por default" as the criterion** — conflicts with the ratified env contract (`DARGENT_LEDGER_CONSUMER_ENABLED` default false, §4 names never change). REJECTED as stated; its substantive core (silent coverage gap) distilled into **DEBT-4**.
- **Roadmap overlap**: refund in the same book = E8 brief (entries [3]+[4], D8 proportional fee reversal); expiration/late = E5; chargeback/holds = stretch — the critique's "what's missing" partially re-discovers M3 and acknowledges "M3 ainda nem começou". Multi-currency/multi-rail: out of scope (PIX BRL only, standing decision).
- **Portfolio/interview ruler**: quality-score frames are not litigated (standing); technical content only.
- **"Mencione Apache Kafka"**: empty header artifact; Kafka remains a standing rejection.

### Actions

- **DEBT-4** (coverage reconciliation) and **DEBT-5** (balanced-entry barrier) registered; recommended addresses E5 and E8 respectively (ratification at commissioning).
- E8 design seeds: reversal-entry pattern; settlement `txid`-overload fix; payout as reconciliation with a real cash account (not `payouts:external` as an accounting well).
- Reusable didactics → `internal-notes/ideias-para-roubar.md` §8.
- Block 1.5 prompt untouched (already issued; these debts are E5/E8-addressed).
## Stop-and-report adjudications — E10 (2026-09-02, drafting phase)

- **Q1 (Block 1.5, dedupe leg)**: ledger-IT2 form adjudicated — full-wiring first delivery, redelivery = direct second `processMessage(raw)` asserting ack + unchanged count; SNS re-publish with distinct dedup id REJECTED (artificial broker state, fan-out already proven by E6, determinism per E7 Q3 precedent). Recorded in the 1.5 prompt addendum.
- **Q2 (Block 2 drafting, spec §7 vs AGENTS §3.7)**: engineer escalated a real conflict — the required `merchantId` query param was an AUTHOR-SIDE spec defect (governance-written). Adjudicated: **tenant from principal** (option 1) — AGENTS §3.7 governs; spec §7 amended in-workspace; `merchant_id` stays as output echo; §8.3's param-negative replaced by a cross-tenant isolation proof (seed 2nd merchant, assert absence). Registered as **TD-17** (closed pending the owner's governance commit). Pattern note: this is §9d succeeding in the direction that matters — the engineer asked instead of choosing, and the constitution won.
- **Q3 (Block 2 drafting, §7 wire naming)**: snake_case (`event_id`, `next_cursor`) vs house camelCase. Adjudicated **camelCase** (TD-18) — PaymentController + envelope fixtures are the convention; Stripe analogy is architectural only; §8.3 gains a drift-guard assertion (`eventId` present, `event_id` absent; no global naming-strategy config). Method note (rule candidate): governance-authored specs state field SETS; wire NAMING follows the codebase convention unless explicitly adjudicated otherwise — two author-side defects (TD-17, TD-18) caught pre-execution by the same §9d mechanism in one drafting cycle.

## E10 Block 1.5 + Block 2 — closure audit (2026-09-02, main `be91286`; combined handoff, 1.5 audited retroactively)

### Chain (curl-verified; total 120 runs)

- **Block 1.5 (#85–#116)**: 27 consecutive REDS (#85–#112 — SNS fan-out fight, jsonb binding saga, multi-schema Flyway war), ALL landed as commits with descriptive fix messages (P1 exemplary); greens #113 `e41baba` (Instant→Timestamp — the real PG blocker), #114 `5b53809` (`NotificationPoisonDlqIT` — the file TD-16 said never existed now exists and redrive-asserts), #115 `09eb26a` (BD-18 un-disabled), #116 `f6212b7` (TD-16 correction: rows #76–#115 with reds + explicit retraction).
- **Block 2 (#117–#120)**: #117 `e6a2a50` ✅ (self-commission + spec §7 TD-17 amendment — see process note), #118 `7a024c5` ✅ (S6 read API), #119 `fa00eb3` ✅ (**flip commit**: README M2 ✅ + CHANGELOG + spec §10 matrix), #120 `be91286` ✅ (**citation commit**, exactly one post-flip citation run, unregistered per #57/#67 precedent — pairing structurally PERFECT).

### Verified clean

- Zero `@Disabled` repo-wide; BD-17 production wiring fixed (`@Qualifier` test bean gone, prod client); scope clean (zero payments/psp/ledger-main touches since `8138f4c`); S6 principal-scoped (`queryPort.findPage(principal.merchantId(), …)`, §3.7 in code), camelCase wire, keyset cursor, no payload; use case deps cleaned; poison IT real (redrive + zero rows); dedupe leg per Q1 (direct 2nd `processMessage`, count stays 1).

### Findings (register: TD-19, TD-20, RAT-E10-IT)

1. **TD-19**: `docs/epics.md` NOT flipped (E10 still ☐; E7 note stale) — flip landed on README only; canonical ledger contradicts README.
2. **TD-20**: response drops `merchantId` (against TD-17 ruling); cross-tenant isolation test missing (TD-17 §8.3); naming-guard negative absent (TD-18).
3. **RAT-E10-IT**: LoopIT/PoisonIT keep test-provided clients/consumers (ledger-harness reality) instead of the 1.5 prompt's "drive the main wiring" text — the prompt's premise about ledger IT practice was wrong; BD-17's substance is fixed. Ratification requested.
4. **Process note**: Block 2 self-commissioned at #117 before this audit (gate bypass); mitigated by owner-relayed adjudications (Q1–Q3) + combined handoff.

### Verdict

**M2 substance stands** (all acceptance artifacts real, chain green, pairing conventional). Closure is INCOMPLETE on: canonical-ledger flip (TD-19) + owner adjudications on TD-20 dispositions and RAT-E10-IT seal. M2's ✅ in README is justified in substance but the canonical ledger must agree before the epic is declared closed by THIS channel.

**Owner adjudications (2026-09-02, same channel)**: TD-19 → flip lands in the owner's governance commit (patch pre-built); TD-20 → FULL rider (cross-tenant test + naming guard + merchantId restored); RAT-E10-IT → seal granted, process note accepted. Closure path: rider lands (content commit + green run + citation) AND owner's governance commit flips epics.md → E10 ✅, M2 ✅ declared by this channel.

**Governance commit verified (2026-09-02 22:02Z)**: `368e76c4a8cf` — epics.md flip + patch + rider prompt landed (3 files; run #121 `33688340781`). Register + verification (this file, now MERGED: repo canonical rows + audit sections) still pending the owner's next governance commit. `internal-notes/ideias-para-roubar.md` deliberately NOT in the public repo (never committed; strategy notes — recommended to keep local).

## E10/M2 CLOSURE — declared by this channel (2026-09-02)

- Rider chain: `79aa8e5abb` (content, run **#123 `33690323704`** green) → `4f3e8f2c1f` (citation, matrix S6-rider row with pair). All TD-20 adjudications verified line-level (echo restored, bidirectional cross-tenant proof, naming negatives). #124 = citation run, unregistered per #57/#67 precedent.
- Governance chain: flip `fa00eb3`/`#119` + citation `be91286`/`#120`; owner commits `368e76c4a8cf` (epics.md E10 ✅ + M2 ✅, run #121 green) and `5794f294c613` (register + merged verification, run #122 green) — API-verified.
- **E10 CLOSED. M2 CLOSED.** Milestones: M0 ✅ · M1 ✅ · M2 ✅. Next: M3 (E5 → E8 → E9).
- Optional one-line touch for the next governance commit: epics.md E10 row tail "TD-20 rider pending (register)" → "TD-20 rider closed (`79aa8e5`/`#123`, citation `4f3e8f2`/`#124`)". Not blocking — the row points at the register, which now says CLOSED.

**E5 commissioned (2026-09-02)**: owner approved the full package — 5 artifacts emitted (`tasks/expiration-reconciliation-e5-prompt.md` + backlog + sequence + spec + block-1 execution prompt), seeded from the pre-adjudicated design seed (2026-08-29) + DEBT-1 (step-0 rider) + DEBT-4 (Block 2 coverage auditor, per its register recommendation). Rider TD-20 chain closed earlier same day (`79aa8e5`/`#123` green + citation `4f3e8f2`/`#124`).

**E5 Block 1 — Q4 adjudication (2026-09-02, step-0 stop-and-report)**: DEBT-1's suspected gap CONFIRMED empirically — the rejecting-contract tests ran RED: `Payment.restore()` silently accepts 3/3 corrupt snapshots (CONFIRMED without fee/net/confirmedAt; non-positive amount; expiry-before-creation). Fix location adjudicated: validation INSIDE `Payment.restore()` — the debt's threat model is a lying adapter, so the defense cannot live in the adapter; restore() is the hydration choke point and the aggregate is the single authority (DEBT-5 philosophy consistency). Main-code widening SANCTIONED, confined to restore() + one domain exception; invariants mirror the factories' own rules, never invented; tests become the permanent contract. Recorded in the block-1 prompt addendum. DEBT-1 (AGENTS §8) closes on the rider's green pair.

## E5 Block 1 — closure audit: APPROVED, all-green chain, DEBT-1 closes (2026-09-02, main `8128eb573c`)

### Chain (API; 5 commits, 5 runs, ZERO reds — local pre-push iteration for the reconciled==0 fixes, no pushed reds to cite)

| Step | Sha | Run | Verdict |
|---|---|---|---|
| 0 DEBT-1 | `242b6e3` | **#125 `33693408878` ✅** (uncited in handoff — sloppy, nothing hidden) | restore() validates by status (IAE + typed InvalidTransitionException, mirrors factories — per Q4 adjudication). DEBT-1 CLOSED |
| 1 migration | `46fad68` | **#126 `33694469538` ✅** (uncited) | landed as **V111** (not V109 — see V-NUM-E5); content EXCEEDS spec: TD-21 index + backfill already in |
| 2 expiration | `a7f626e` | #127 `33700561182` ✅ | scheduler + IT |
| 3 reconciler | `48ade01` | #128 `33706674658` ✅ | scan per TD-21, `Timestamp.from` binds, PSP WireMock, amount-mismatch guard |
| 4 resurrection | `8128eb5` | #129 `33707174938` ✅ | scenarios 11+27, exactly-once |

### Line-level verified

- Create-path initialization implemented (`Payment` schedules `next_reconcile_at = now + first rung` at creation — TD-21 co-amendment; no "born given-up" bug). Give-up clears to NULL. `late=true` + audit command names per spec. Scope clean (zero ledger/notifications/psp prod touches). Zero `Thread.sleep`/`@Disabled` in E5 tests. MigrationIT extended (V111 applies on real PG).
- Engineer's 3 root-cause fixes (Instant→Timestamp binding, 25-char txid in seed, unquoted endToEndId in stub) — all plausible and consistent with the all-green chain.

### Adjudications on this audit

1. **V-NUM-E5** (register): KEEP V111; V107/V109 gap history; next payments migration V112; spec §2 annotated; the missed disclosure noted (minor §9d miss).
2. **Uncited green runs** for steps 0/1: noted; P1 discipline is for reds — but pairs must be cited in full going forward (number AND id, every step).

### Verdict

**Block 1 APPROVED. DEBT-1 CLOSED.** Block 2 commissioned: give-up window IT, scenarios 9/10 legs, DEBT-4 coverage auditor, docs amendment (spec sync with TD-21/V111 as-built + register rows + README TD-15-sentence flip at S8) + E5 row flip + citation.
