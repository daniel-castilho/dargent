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

(#15/#16 predate this audit's verified window; verify locally before citing.)

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
