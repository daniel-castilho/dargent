# Execution Prompt — E10 Remediation Block 1.5: BD-17 + real S5 + TD-16 docs-correction (+ BD-18 rider)

**Issued:** 2026-09-02 · **Executor:** the AI Software Engineer · **Auditor:** the governance side
(API-audited: messages vs diffs, run pairs by number AND id, reds ALWAYS cited, greps at the cited commit).
**Nature:** remediation of the audited Block 1. Block 1 is NOT complete: S1–S3 accepted; S4 blocked by
BD-17; S5 missing; citation layer defective (TD-16). Block 2 (S6/S7/M2 flip) stays locked until this
block closes. **Push is the owner's action.**

---

## Where you are starting from (audited facts — cite pairs, never memory)

- main = `8138f4c9d2e70bb5a8604841be68b3b7fb5fceb6` ("disable NotificationLoopIT in CI"), run **#84
  `33581153906`** green. Full verified chain: #76 `5919275` ✅ · #77 `c1b435c` ✅ · #78 `98c4be0` ✅ ·
  **#79 `30245af` ❌ RED** · #80 `d47eec4` ✅ · #81 `8565060` ✅ · #82 `9a13f76` ✅ · **#83 `db82f60` ❌ RED** ·
  #84 `8138f4c` ✅ (green by disabling a test — the defect this block removes).
- **BD-17 (blocker, wiring):** `NotificationCompositionConfig.sqsNotificationConsumer` (MAIN) injects
  `@Qualifier("notifsTestSqsClient") SqsClient` — a bean that exists ONLY in the IT's
  `@TestConfiguration` (`NotificationLoopIT` line ~379). Production boot with
  `DARGENT_NOTIFS_CONSUMER_ENABLED=true` fails (`NoSuchBeanDefinitionException`). CI never enables the
  consumer, so the defect is invisible; the disabled IT is what used to expose it.
- **TD-16 (false citations):** `NotificationPoisonDlqIT` was cited as "disabled with @Disabled" in the
  `db82f60` commit message and the Block 1 handoff — the file NEVER existed (only `NotificationLoopIT`
  exists, ONE happy-path test). S1/S4 sha↔run pairs were mislabeled and reds #79/#83 were not cited.
- **BD-18 (pre-existing):** `apps/api/src/test/.../provisioning/DevApiKeyProvisionerTest.java` line ~42
  `@Disabled("Flyway context loading issue — to be fixed in S8")` — "S8" refers to no live plan.
- Reference implementations (copy the shape): `LedgerCompositionConfig` (plain `ledgerSqsClient` bean,
  no qualifier), `LedgerMoneyLoopIT` / `LedgerPoisonDlqIT` (topology provisioning + assert discipline).

## Sources of truth — binding

`tasks/notifications-e10-spec.md` §6/§8 (unchanged — reality moves to the spec, never the reverse) ·
`tasks/notifications-e10-sequence.md` P1–P6 · AGENTS §9d · playbook §7 (quarantine rules) · the TD-16
correction protocol (docs-correction closes only with a post-commit grep citing the SHA).

## Fix 1 — `fix(notifications): wire consumer to production sqs client, enable loop IT (BD-17)`

1. `NotificationCompositionConfig`: `sqsNotificationConsumer` injects the production `notifsSqsClient`
   bean directly (no qualifier) — mirror `LedgerCompositionConfig` exactly. No new beans, no renames,
   env §4.1 untouched.
2. `NotificationLoopIT`: DELETE the `notifsTestSqsClient`/`notifsConsumer` overrides — the IT must drive
   the MAIN wiring exactly like the ledger ITs do (real client from `AWS_ENDPOINT_URL` properties, real
   consumer bean, `runOnce()` driven deterministically). Fix the LocalStack SNS→SQS fan-out for real by
   mirroring the ledger ITs' topology provisioning mechanics; quote the ROOT CAUSE of the previous
   "subscription not delivering" in the handoff. Add the missing dedupe leg: same event delivered again →
   `runOnce()` → still ONE row, zero new writes, ack (spec §8.1).
3. Remove the `@Disabled` (line ~67). The test runs in CI, green, on the main wiring.
4. STOP-AND-REPORT (never disable, never bypass the main wiring) if: the fan-out issue persists after
   mirroring the ledger mechanics; or the main client cannot work in the IT without test-only overrides.

## Fix 2 — `test(notifications): poison dlq integration test (E10 S5)`

1. Create `apps/api/src/test/java/io/dargent/api/notifications/NotificationPoisonDlqIT.java` mirroring
   `LedgerPoisonDlqIT` mechanics and assert discipline: malformed body → not acked → after maxReceive
   attempts the message lands in the notify DLQ (spec §8.2 — the deliverable falsely claimed before).
2. The name `NotificationPoisonDlqIT` becomes TRUE by existing — the citation defect closes by delivery,
   not by rewording.

## Fix 3 — `test(api): enable DevApiKeyProvisionerTest (BD-18)` — owner decision 2026-09-02

1. Root-cause the "Flyway context loading issue" in the test harness (test properties/context layout —
   ledger/api IT harnesses are the reference); remove the `@Disabled`; test green in CI.
2. If the fix requires MAIN-code changes or reveals a real provisioning defect: STOP-AND-REPORT with the
   diagnosis. Do not widen scope silently.

## Fix 4 — `chore(notifications): remove unused dependencies and stale comments (E10 audit notes)`

1. `NotificationIngestionUseCase`: drop the unused `jdbc`/`clock` constructor params and fix the stale
   "terminal status / event row" comment (the notifications table has no status column; say what the
   code does: read → single idempotent insert → ack; poison → false).
2. `NotificationApplicationConfig` javadoc: replace the copy-pasted "ledger HTTP surface" with the
   notifications reality. Comment = code everywhere.

## Fix 5 — `docs(e10): correct block 1 citations and record remediation pairs (TD-16)` — LAST

1. Canonical table (`tasks/e3r-block1-verification.md`): append rows #76–#84 with the CORRECTED
   sha↔run mapping INCLUDING the reds (#79, #83 — conclusion `failure`), the retraction of the
   `NotificationPoisonDlqIT` "disabled" claim (it never existed), and this block's pairs.
2. `tasks/notifications-e10-spec.md` §10 matrix: fill S0–S5 rows with real pairs; S5's evidence is this
   block's green runs.
3. **Correction grep (TD-14 protocol):** after the docs commit, run
   `grep -rn "disabled" tasks/notifications-e10-spec.md tasks/e3r-block1-verification.md` and paste the
   output WITH the commit SHA in the handoff — plus `grep -rln "NotificationPoisonDlqIT" apps/api/src/test`
   showing the file that now truly exists.

## Order & discipline

1 → 2 → 3 → 4 → 5. Each commit pushed green before the next; every red run cited in the handoff with
its id and your written explanation (P1); run pairs cited number AND id; no `@Disabled`, no skips, no
`Thread.sleep` in tests; env names untouched; commit message = diff (pre-push hunk check).

## Scope & stop conditions

- Allowed: `NotificationCompositionConfig` (main wiring — sanctioned exception, BD-17),
  `apps/api/src/test/**` (notifications ITs + provisioning test), `modules/notifications` (Fix 4
  hygiene only), `tasks/` docs. Zero lines in `modules/payments`, `modules/ledger`, `apps/psp-simulator`.
- STOP-AND-REPORT on: any red you cannot explain in writing; any felt need to keep a test disabled;
  any fix that requires bypassing the main bean graph; any schema/env change.

## Handoff report (API-audited)

- The five commit shas + messages; run pairs (number AND id) INCLUDING reds; the fan-out root cause
  quoted; the BD-17 wiring diff quoted; the BD-18 root cause; correction greps with their commit id;
  exact head sha at handoff time.

Then stop. On verified evidence: BD-17, TD-16, BD-18 zero out; Block 1 closes honestly (S1–S5); and
**Block 2 (S6 read API + S7 docs + flip + M2 closure) is commissioned**.

---

## Clarifications — adjudicated (2026-09-02; mechanics only, auditor-final)

1. **Fix 1 dedupe leg mechanics (Q1 — engineer asked BEFORE diverging; §9d satisfied):** ADJUDICATED =
   the **ledger-IT2 form**, as recommended. First delivery goes through the FULL wiring (relay → SNS →
   SQS → consumer `runOnce()` → row, per the main-wiring requirement); the **redelivery leg is a direct
   second `processMessage(raw)` call** asserting ack=true + `notificationCount()` unchanged (zero new
   writes) — same altitude and shape as `LedgerMoneyLoopIT` IT2 and the E7 BD-15 Q3 adjudication.
   **REJECTED: SNS re-publish with a distinct dedup id**, three reasons: (a) artificial scenario — in
   production a duplicate of the same event arrives via SQS at-least-once redelivery (visibility
   timeout), because SNS FIFO dedup would swallow a second publish of the same event; the distinct-id
   variant tests a broker state production does not produce; (b) SNS→SQS fan-out is already proven by
   E6's broker-behavior ITs — re-proving it here is out of scope; (c) determinism — zero LocalStack
   dedup windows, zero timing dependence (the exact rationale of the E7 Q3 ruling, by precedent).
   Rationale note: the dedupe contract lives in the use case + DB UNIQUE (spec §4/§5); consumer
   translation is already pinned by `SqsNotificationConsumerTest` + the happy-path leg. Assert with the
   same count helper as leg 1; no new helpers, no sleeps.
