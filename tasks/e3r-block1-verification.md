
**2nd maturity analysis triaged (2026-09-02)**: baseline CORRECTED — Observable 1.5 → 2.0 (RequestIdFilter is real: E3 §5.4, MDC + echo + outbox carry; caught by the external analysis, verified in-repo). New register row **TD-22** (README stack row announces JSON logs + Micrometer/Prometheus in present tense; LOW; disposition at E11 commissioning — recommended rider E11 S0). Gap disclosure: executed threat model absent from all milestones (candidate E13/E14 addendum). Convergence note: the analysis's priority order (M3 → M4 gates → k6) matches the canonical roadmap exactly. Stale parts rejected with evidence (E5 closed + CI-proven; reconciler-in-CI is TRUE since #127–#131; refunds literally landing — `57b08a8` S2 observed during triage). Full triage in `internal-notes/maturity-assessment.md` (local).

## Carry-forward — maturity conversation → future deliverables (owner directive, 2026-09-02: "os resultados devem refletir nos próximos entregáveis")

**Author-side pre-flight checklist — STANDING, effective immediately, applies to every governance-authored artifact (spec, baseline, prompt, matrix) BEFORE emission:**

1. **Grep-before-claim**: any statement about current as-built state (what exists/doesn't in code) requires a grep/read at a pinned SHA in the same session — the Observable 1.5 miss (RequestIdFilter existed, unread) is the precedent. Baselines/specs cite the SHA they were checked against.
2. **Flow-level adversarial read** (TD-21 lesson): for every scan/selector/branch in a spec, ask "which rows/states does this NOT see, and does any acceptance IT require seeing them?" — contradiction with my own §7 ITs is a TD-class defect.
3. **Surface naming follows the codebase** (TD-18): field SETS in specs; wire naming = house convention unless explicitly adjudicated.
4. **Constitution check on every API surface** (TD-17): AGENTS §3.7/§4.1/§2.4 re-read per endpoint/env/DDL before publishing §5/§7.
5. **Acceptance-name case check** (ReconcilerGiveUpIt lesson): every IT name in a spec must match the failsafe pattern `**/*IT.java` exactly — lowercase-It silently skips (a hidden missing test).
6. **Stop-and-report is also MY obligation** (mirror of §9d): a governance-authored defect caught post-emission gets a TD number with the same protocol the engineer obeys — no silent edits.

**Deliverable mapping (what lands where):**

| Future deliverable | Carries from this conversation |
|---|---|
| **E9 package** (delivery hardening — closes M3) | Standard honesty riders at S0 (README drift check per TD-22 class); flip closes M3 → maturity matrix row "Reliable 4.5→5.0 condition" gets its evidence input |
| **E11 package** (observability) | **Block order re-shaped**: Block 1 = request-correlation hardening + JSON/ECS logging FIRST (before metrics — the "3am incident" principle); **TD-22 rider at S0** (README stack row recast); NEW acceptance criterion from the external analysis: **on-call drill IT — "a dangling txid is findable from logs+correlation alone in ≤2 minutes"** (named scenario, runs in CI); `dargent_*` metrics catalog per observability.md; actuator lockdown = scenario 28 |
| **E13 package** | Existing full-force seed (spotpobre/flowtxt, SHAs re-pinned) **+ threat model as candidate addendum** (gap disclosed by the 2nd maturity analysis — owner picks at commissioning) |
| **E14 package** | Restore drill already planned; threat model second home if E13 declines |
| **Post-M4** | Maturity re-assessment per `internal-notes/maturity-assessment.md` protocol (falsifiable predictions on file) |

The conversation's outputs are now mechanical obligations with addresses — none depend on memory.
**E8 Block 1 — Q8 adjudication (2026-09-02, P4 flake STOP)**: RefundFlowIT golden-vector red in CI ×2 (green locally; the `available==9900` assert PASSES in CI) — STOP approved, block held. Paradox flagged: `9900 >= 3960` is true, so the quoted condition cannot be the literal failing predicate — exact IGNORED note + balances snapshot demanded. Prime hypothesis H1 (auditor): shared fixed MERCHANT across ledger ITs + singleton container → cross-IT contamination (LedgerSettlementIT drains the same merchant) under CI interleaving; local green = different interleaving. Fix direction: per-IT unique merchant (permanent test-only hardening). No quarantine/disable (money-guarantee test — playbook §7). One diagnostic rerun allowed; green ≠ closure. Provenance: shared-constant pattern = auditor prompt heritage. Recording pending in verification trail with both CI run ids from the engineer's next message.
**E8 Block 1 — Q9 adjudication (TD-23)**: RefundFlowIT CI red root-cause family identified via the guard's arithmetic (IGNORED + true-looking condition = drain saw a different row, balance 0). H1 (sibling-IT drain) refuted — auditor memory error (LedgerSettlementIT died in the E7 JPA-to-JDBC conversion). Fix adjudicated green-path: single-place account-id normalization (UUID-derived, both writers + reader) + uppercase-seed regression test. Block 1 held red; closes on the post-fix green pair citing all three red run ids + diagnostic commit 4701ee0 (kept). Register lesson: when a balance/conditional guard reports the impossible, check WHICH ROW it saw before WHY it saw it — string identity of account keys is a first-class suspect in any multi-writer ledger.
**E8 Block 1 — Q10 adjudication (TD-23)**: H2 (account-string case divergence) REFUTED by the engineer's focused read (byte-identical lowercase keys at confirm writer / drain / assert, line refs). Auditor score: H1 and H2 both dead; the surviving constraint is arithmetic (same key + different observed value = different DB state). Q10 order: Option 2 wiring read first (free — bean identity of JdbcClient/DataSource/txTemplate feeding postRefund vs confirm path; context-cache duplication check), Option 1 upgraded probe second if clean (resolved key + BOUND :drain value + in-tx SELECT of K, one CI run). Engineer's refutation quality noted — line-referenced reads beat auditor hypotheses twice in a row; the discipline that survived is the guard's own arithmetic. Block 1 held red.

**E8 Block 1 — TD-23 RESOLVED (2026-09-02, fix `8bad5e8` awaiting push/audit)**: dual root cause — (1) routing gap (refund.created IGNOREd at birth in ALL pushed commits; the branch lived only in the working tree → CI/local divergence was TREE-STATE, never environment); (2) double drain (postRefund conditional drain + postJournal upsert reapplication = −netDrain twice; 9900→1980) — a real money bug caught by the golden vector's exact assert. Auditor scorecard: H1 (sibling drain), H2 (account string), H3 family (datasource/wiring) — all dead; the two winning moves were the engineer's byte-identity read and the commit that finally diffed tree-vs-pushed. **Rule candidates born here (register-grade):** (i) *tree-state before environment* — CI-only + local green ⇒ `git status`/`git diff HEAD` FIRST; local runs count only against the committed tree; (ii) *entry-point-first probing* — instrument whether the path EXECUTED before how it misbehaved (the 2a5dd97 probe would have been silent in CI, and its silence would have been the diagnosis). Golden-vector vindication: the exact-5940 assert is the only reason the double drain never shipped.

## E8 Block 1 — closure audit: APPROVED (TD-23 CLOSED; S4 false-green disclosed) (2026-09-02, main `8bad5e8c91244ba57be61d7c36c57e9e5356f87d`)

### Chain (API-verified; 10 commits, 4 reds — all cited here incl. the one the handoff missed)

| Step | Sha | Run | Verdict |
|---|---|---|---|
| S0 DEBT-5 | `8dae52d` | #137 `33716983938` ✅ | barrier BEFORE traffic (adjudicated order) |
| S1 V112 | `867e673` | #138 `33719126880` ✅ | refunds table + checks |
| S2 use case | `57b08a8` | #139 `33763871329` ✅ | D8 + balance guard |
| S3 REST + guard | `40d193a` | #140 `33779134991` ✅ | endpoint ITs |
| S4 consumer | `c87f5b8` | #141 `33790243173` ✅ | **FALSE-GREEN — disclosed by the engineer**: green only because routing was absent (refunds IGNOREd from birth); true S4 behavior proven by the fix's run |
| S5 golden vector | `abf08b2` | #142 `33805190767` ❌ | red 1 |
| S5 event-status | `9e5aa19` | #143 `33807564002` ❌ | red 2 |
| S5 pre-drain assert | `4701ee0` | #144 `33824300177` ❌ | red 3 (assert kept as permanent pre-drain guard) |
| probe (Q10) | `2a5dd97` | **#145 `33825860669` ❌ — 4th red, NOT cited in the handoff** (P1 gap; red because the bug was upstream — probe would have been silent; cited here) | reverted by the fix |
| FIX | `8bad5e8` | **#146 `33827336501` ✅** | dual root cause fixed; TD-23 CLOSED |

### Five audit questions — verified against the diff (not just the handoff's word)

1. Routing: single new branch (`refund.created`, line 73); unknown → IGNORED unchanged (lines 111–114). ✓
2. `postJournal` byte-identical; JdbcLedgerStore's 16 deletions = probe revert; `postJournalWithoutBalances` refund-only. ✓
3. Net available delta = −netDrain exactly once (conditional drain); postings [3]+[4] net −3960 (journal truth) — projection == Σ lines invariant HOLDS (5940/−6000/60 asserted + proof ok). ✓
4. DEBT-5 barrier covers the new path (same validated ctor; 4-posting refund journal ΣDR=ΣCR=4040). ✓
5. Redelivery no-op (ON CONFLICT + findEventStatus POSTED → ack zero writes); both IT legs green. ✓
BONUS: the refund path implements the BD-15 resume pattern (RECEIVED → resume; DIVE → re-read) — E7's medicine applied by construction.

### Verdict

**(Auditor self-correction, same session:** the first draft of this table swapped S1's and S3's run ids — the TD-13 class, in the very note repairing citation gaps. Fixed above; correct pairs: S1 `867e673`→#138 `33719126880`, S3 `40d193a`→#140 `33779134991`.)**

**TD-23 CLOSED. E8 Block 1 (S0–S5) APPROVED — with two recorded notes: (a) the 4th red (#145) was uncited in the handoff (P1 discipline gap, small but real); (b) S4's false-green is the second honest disclosure of the block — the pattern "green because the path never ran" is now a named hazard for event-consumer epics.**

### Direction (owner relay)

Block 2 is commissioned (S6 races + insufficient-balance IGNORED → S7 DEBT-4 auditor legs → S8 docs). Per plan: CHANGELOG entries, docs sync (design/playbook/standards), acceptance-matrix rows and the epic flip ALL land at S8 in one docs pass — not before the races/auditor proofs exist. "Milestone DoD": M3 flips ONLY with E9 — E8 flips its own row at S8.

## E8 Block 2 — closure audit: APPROVED → **E8 CLOSED** (2026-09-03, main `694760235da6a688ed67176630a0a153bd98cf16`)

### Chain (API-verified: 4 commits, runs #147/#148 green; the handoff's commit table was mangled — shas recovered from API)

| Step | Sha | Run | Verdict |
|---|---|---|---|
| S6 guard+races | `369b0c6` | (green; id in epics citation) | RefundBalanceGuardIT 4T (insufficient→409 zero-writes / pass→5940/−6000/60) + RefundRaceIT 4T (sc.12 [201,409] refunded=6000 never 12000; sc.23 posted1/ignored1/skipAudit1, available 4000, projection balanced) |
| S7 auditor legs + leak | `7993f1f` | (green) | legs (c)/(d) + connection-leak fix (.stream().collect lazy → .list() terminal; real pre-existing defect surfaced under E8 IT load — paid) |
| S8 flip+docs | `c8af90b` | **#147 `33831934579` ✅** | epics E8 ✅ (M3 ☐ preserved: "M3 completes with E9"), README (money-flow refund line + M3 row), CHANGELOG +22, design.md +10, AGENTS §8, matrix §10, package tasks landed |
| citation | `6947602` | **#148 `33832352522`** ✅ | cites S6/S7/S8 shas + run #147; unregistered per #57/#67 |

(The handoff named two HEADs — `c8af90b` in greps, `6947602` in status — both real: flip and citation. Mangled table, honest content.)

### Findings → register

- **TD-24**: DEBT-5 number collision (AGENTS leak vs register/commit barrier). Disposition: renumber leak → DEBT-6.
- **TD-25**: `RefundEndpointIT` never existed (spec §7.2 name); substitution (RefundBalanceGuardIT HTTP asserts) RATIFIED; **Block-1 audit missed the absent name — second auditor gap this epic** (after the GiveUpIt typo). Spec annotated.
- Convention notes (recurring): engineer landed the governance tasks package + AGENTS edits at S8 again (E5 precedent — content correct, owner-commit convention bending); the stale-build ClassNotFound flake was local-only (no dark changes).

### Verdict

**E8 CLOSED.** Money now returns with the same guarantees it leaves: D17 lock, D8 proportional reversal, conditional drain (never-negative), reversal-not-edit journals, coverage auditor watching both directions, DEBT-5 barrier refusing unbalanced state. **M3 remains ◐ — one epic left: E9 (delivery hardening).**
**E9 commissioned (2026-09-03)**: owner approved the package — 5 artifacts emitted (`tasks/delivery-hardening-e9-prompt.md` + backlog + sequence + spec + block-1 execution prompt). Anchored on the as-built relay (E6 left it 90% built: SKIP LOCKED claim, 30s/2m/5m ladder frozen, EXHAUSTED unreachable — 'E9 owns EXHAUSTED' was the work order waiting in Policy.maxAttempts). Pre-adjudicated: zero migrations target; maxAttempts=3; republish mints deterministic `{eventId}-r{n}` ids (tool re-run idempotent at consumers); admin gate via single env key (minimum honest guard); scenario 20 proven by consumer dedupe. E9 flip carries **M3 ✅** (E5+E8+E9 chain). Standing maturity-carry rules active (grep-before-claim, failsafe case check, docs-honesty riders at flip).
**3rd external analysis triaged (2026-09-03) — owner adjudicated 4/4 as recommended.** Fact-checked
against tree @`6947602` before adoption (11 claims confirmed, 6 refuted/stale). Minted:
- **TD-26**: README guarantee-table present-tense claims for M4/M5 mechanisms (lines 26-27 "shutdown-under-load
  gate in CI" / "security gates in CI"; stack rows 107/109) + `slos.md` citing `dargent_*` SLIs without
  status markers. Disposition: docs-honesty rider on **E9 S6** (annotate `(M4)`/`(M5)`, never delete) —
  folded into spec §7.1 + backlog S6 pre-commit.
- **DEBT-7**: `postJournal` × `postJournalWithoutBalances` duplicated journal_entries+postings SQL
  (born E8 S5, the TD-23 hotfix price). Disposition: consolidation at **M4 refactor window** with its
  own IT evidence; explicitly OUT of E9 (audited money SQL, zero scope creep).
- **Riders registered (no register number — destination epics)**: E13 = prod-profile boot fail-fast on
  default DB credentials; E11 = log-scrubbing assertion (Authorization/api keys never in JSON ECS output);
  E13/threat-model addendum = ledger admin segregation for rebuild/proof/settlement (pattern precedent:
  E9's `DARGENT_OUTBOX_ADMIN_KEY`; any env via §4.1, so future-epic only); **post-v1.0.0 epic candidate**
  = PSP devolution rail for refunds (+ honest limitation note lands E9 S6).
- **Refuted, not adopted**: "settlement reuses journal txid as idempotency key" (false — caller-supplied
  header key, `LedgerController:62`; settlement entry txid=null); "reconciler sold but unproven" (stale —
  live since `f6ede8a`; missing piece is scraping, E11); "README lies about deploy" (half — lines 152-162
  disclose M4 scope; the defect is only the guarantee table); "guarantee is a flag" (default-off ratified,
  not reopened); SQL-in-use-case (wrong locus — SQL lives only in `JdbcLedgerStore`; the real finding is
  the in-store duplication → DEBT-7). Full fact-check: `internal-notes/maturity-assessment.md` (local).
