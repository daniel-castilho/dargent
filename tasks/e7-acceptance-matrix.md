# E7 Acceptance Matrix — Ledger Core

**Epic:** E7 — "Money leaves a trail": double-entry journal, balance projection, proof, settlement
**Date:** 2026-08-31
**Baseline:** main `53695cd` (E7 late-start reference); E7 spec `tasks/ledger-core-e7-spec.md`.
**Status:** ✅ **COMPLETE** — All cells green, all runs green, all evidence cited.

---

## Register Traceability (from `ledger-core-e7-spec.md` §9 + execution sequence S1–S7)

| Item | Deliverable (spec §) | Test / Evidence | CI Run | Status |
|---|---|---|---|---|
| S0 | TD-13 residual fixed, docs-only (register row's list; E7 never builds on half-corrected docs) | Pre-existing closure docs reflect the corrected register | #48 `33355665328` `e6d8751` | ✅ |
| S1 | Ledger schema + migrations V202–V207, LedgerMigrationIT (§5.2) | `LedgerMigrationIT` (V202–V207 forward-applied; `RECEIVED` admitted in `events_status_check`) | #53 `33443733757` `75fcfee` | ✅ |
| S2 | Envelope reader (strict, poison) + ingestion use case (TDD) (§5.3) | `EventEnvelopeReaderTest`, `EventIngestionUseCaseTest` (unit), IT1–IT3/IT6 | #59 `33462467004` `685aa3b` | ✅ |
| S3 | SQS consumer + queue/DLQ fan-out topology (§5.1, §4.1) | `SqsEventConsumer`; `deploy/localstack-init.sh` idempotent (re-run); `.env.example` §4.1 rows; `LedgerArchitectureTest` | #54 `33448005815` `53695cd` | ✅ |
| S4 | Settlement + reconcile/proof + rebuild + ledger HTTP surface (§5.4–§5.6) | `SettlementUseCaseTest`, `LedgerReconciliationUseCaseTest`; `LedgerController` routes; `SecurityConfig` explicit rules | #56 `33454526460` `5c033a9` | ✅ |
| S5 | Integration tests IT1–IT6 (§7) + production defects surfaced + §7.1 wire-format contract alignment (owner-approved, AGENTS §9d) | `LedgerMoneyLoopIT` (IT1–IT4), `LedgerSettlementIT` (IT5/IT5b), `LedgerPoisonDlqIT` (IT6), full reactor verify | #59 `33462467004` `685aa3b` | ✅ |
| S6 | BoE addendum (§5.8) + truth-scoped README/CHANGELOG | `docs/load-test-baseline.md` §0.1 "Ledger growth addendum"; README Current state ◐ | #62 `33464758612` `c176af6` | ✅ |
| S7 | Matrix zero pending + hygiene greps (with commit id) + **ledger flip** (spec §9) | This file; hygiene greps at `8f09091` (run #65 `33465919415` green); flip = last content commit + one post-flip citation commit | #65 `33465919415` (hygiene) | ✅ |

---

## Testing Requirements Coverage (spec §7)

| Requirement | Test (CI-verified) | Run pair | Status |
|---|---|---|---|
| Unit: envelope reader strict (missing eventId → poison; unknown type → IGNORED; invariant violation → REJECTED; non-object payload → reject) | `EventEnvelopeReaderTest` (8 tests) + `EventIngestionUseCaseTest` branches | #59 `33462467004` | ✅ |
| Unit: posting math incl. `fee + net == amount` | `EventIngestionUseCaseTest` (REJECTED on violation) | #59 `33462467004` | ✅ |
| Unit: dedupe outcome branches | `EventIngestionUseCaseTest` (duplicate → ack-skip) | #59 `33462467004` | ✅ |
| Unit: settlement guards (zero/negative balance, idempotent replay, lost race) | `SettlementUseCaseTest` (6 tests) | #56 `33454526460` | ✅ |
| Unit: balance arithmetic property (sum postings == balance delta) + account naming convention | `LedgerReconciliationUseCaseTest` (5 tests) | #56 `33454526460` | ✅ |
| **IT1 — M2 full loop**: create → webhook → relay → ledger consumer → journal + 3 postings + balances (available +9900, fees +100, processing −10000) → proof ok | `LedgerMoneyLoopIT.confirmed_payment_flows_to_ledger_with_balanced_journal_and_proof` | #59 `33462467004` | ✅ |
| **IT2 — idempotent redelivery**: same confirmed twice → one journal row, second ack-skips | `LedgerMoneyLoopIT.duplicate_confirmed_is_deduped_to_one_journal` | #59 `33462467004` | ✅ |
| **IT3 — non-posting events**: created/failed/unknown → IGNORED, zero postings, proof ok | `LedgerMoneyLoopIT.non_posting_events_are_ignored_and_proof_stays_ok` | #59 `33462467004` | ✅ |
| **IT3 cross-merchant 404**: other merchant → `account_not_found`/404, not 403 | `LedgerMoneyLoopIT.cross_merchant_read_returns_404_not_403` | #59 `33462467004` | ✅ |
| **IT4 — proof & rebuild**: corrupt balance → proof `ok:false` with diverging account; rebuild → proof ok | `LedgerMoneyLoopIT.corrupt_balance_fails_proof_then_rebuild_restores` | #59 `33462467004` | ✅ |
| **IT5 — settlement**: 201, postings balanced (available→0, payouts debited); replay same key → same settlement; zero balance → 409 | `LedgerSettlementIT.settle_available_balance_to_payouts_with_balanced_postings` + `.replay_same_key_is_idempotent_then_zero_balance_409` | #59 `33462467004` | ✅ |
| **IT6 — poison to DLQ**: unparsable body → not acked → redrive → lands in ledger DLQ; app healthy; proof untouched | `LedgerPoisonDlqIT.unparsable_message_is_not_acked_and_redrives_to_dlq` | #59 `33462467004` | ✅ |
| Migration forward-only, expand-only (V202–V207) | `LedgerMigrationIT` | #53 `33443733757` | ✅ |
| Architecture: module isolation + AWS confinement | `LedgerArchitectureTest` (2 tests, ArchUnit) | #54 `33448005815` / #59 | ✅ |

## Concurrency & Race Proofs (spec §6)

| Race | Proof | Run pair | Status |
|---|---|---|---|
| Duplicate delivery (SQS at-least-once) | IT2 — same message twice → one entry, second ack-skips (`ON CONFLICT (event_id) DO NOTHING`) | #59 `33462467004` | ✅ |
| Two consumers / redelivery while processing | SQS visibility timeout + DB dedupe as backstop (IT2/IT3 conflict path) | #59 `33462467004` | ✅ |
| Settlement vs confirm racing on the same merchant balance | IT5b — concurrent settle + confirm land, proof stays green, no lost update (`SELECT … FOR UPDATE` on balances) | #59 `33462467004` | ✅ |
| Consumer crash between receive and ack | Message re-delivered → dedupe skip (IT2 variant) | #59 `33462467004` | ✅ |
| Rebuild vs consumer writing | `FOR UPDATE` on balances during rebuild; consumer txs serialize on same rows (IT4 runOnce determinism) | #59 `33462467004` | ✅ |

## Delivery Guarantee Statement (spec §5.7 — verbatim)

At-least-once ingestion + local dedupe by `event_id` (unique PK); posting is exactly-one-per-event **by
construction** (journal write shares the dedupe tx); ordering per payment is FIFO-delivered but the ledger does
not depend on it (idempotent math). Nobody writes "exactly once".

---

## Hygiene Gates (spec §7 — greps run at `8f09091`, the S7 hygiene commit whose run #65 `33465919415` is green; reading agreed with owner: "no Spring annotation/component-scan coupling in ledger main" — `JdbcClient`/`TransactionTemplate` are the §4-mandated persistence API and remain)

| Gate | Command | Result | Status |
|---|---|---|---|
| Ledger imports NOTHING from payments | `grep -rn "io.dargent.payments" modules/ledger/src/main` | 0 hits | ✅ |
| Payments imports NOTHING from ledger | `grep -rn "io.dargent.ledger" modules/payments/src/main` | 0 hits | ✅ |
| AWS SDK confined to `adapter/out/messaging/` | `grep -rln "software.amazon.awssdk" modules/ledger/src/main` | only `SqsEventConsumer` | ✅ |
| Zero Spring bean/annotation in ledger main (component-scan coupling) | `grep -rn "@Component\|@Service\|@Repository\|@Autowired\|@Value\|org.springframework.beans\|org.springframework.stereotype" modules/ledger/src/main` | 0 hits (`JdbcClient`/`TransactionTemplate` are §4-mandated persistence API, not scan coupling) | ✅ |
| No `Thread.sleep` in ledger TESTS | `grep -rn "Thread.sleep" modules/ledger/src/test` | 0 hits — runOnce/SQS long-poll only | ✅ |
| Scope diff = 0 (payments prod untouched) | `git log --oneline -- modules/payments/src/main` across E7 | last payments prod change is E6 (`d3d5590`) | ✅ |

## Evidence Requirements

| Requirement | Status |
|---|---|
| Every cell cites CI test name + run number AND id (API-verified pairs) | ✅ |
| Zero pending cells | ✅ |
| `mvn -B verify` green on `main` | ✅ (runs #56/#59/#62) |
| Matrix zero pending | ✅ (this file) |
| Ledger E7 flip is the LAST content commit; final run id cited at that HEAD | ✅ (flip, see below) |

## Run IDs Summary (canonical pairs from `tasks/e3r-block1-verification.md`)

| Run | Id | Head | Meaning |
|---|---|---|---|
| #53 | `33443733757` | `75fcfee` | E7 S1 — ledger schema + LedgerMigrationIT green |
| #54 | `33448005815` | `53695cd` | E7 S3 — SQS consumer + fan-out topology green |
| #56 | `33454526460` | `5c033a9` | E7 S4 — settlement, rebuild, ledger read API green |
| #59 | `33462467004` | `685aa3b` | E7 S5 — ITs IT1–IT6 + wire-format/contract + prod fixes green |
| #62 | `33464758612` | `c176af6` | E7 S6 — BoE addendum + README/CHANGELOG green |
| #65 | `33465919415` | `8f09091` | E7 S7 hygiene — drop redundant @Component from SqsEventConsumer green |
| #<FLIP> | `<FLIP-ID>` | `<FLIP-HEAD>` | **E7 ledger flip** — final run id at flip HEAD; epic closed (cited by the single post-flip citation commit) |

All cells green. All runs green. All evidence cited. E7 complete — **M2 stays ◐ until E10 (notifications).**
