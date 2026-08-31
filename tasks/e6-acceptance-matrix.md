# E6 Acceptance Matrix — Outbox & Messaging Backbone

**Epic:** E6 — "The event leaves the database": relay the outbox to SNS/SQS FIFO with DLQ and retention  
**Date:** 2026-08-30  
**Baseline:** main `3b60ba8` (E3R closed); E6 spec `tasks/outbox-messaging-e6-spec.md`.  
**Status:** ✅ **COMPLETE** — All cells green, all runs green, all evidence cited.

---

## Register Traceability (from `outbox-messaging-e6-backlog.md` S0–S7 + spec §9)

| Item | Deliverable (spec §) | Test / Evidence | CI Run | Status |
|---|---|---|---|---|
| S0 | TD-13 citation-layer correction (docs-only, BEFORE any E6 code) | `tasks/e3r-acceptance-matrix.md` register ids 1:1; artifact index E6 row | #31 `33336657975` | ✅ |
| S1 | `OutboxId` UUIDv7 VO + writers switched (§5.5) | `OutboxIdTest` (version nibble, variant, monotonic under fixed clock) | #32 `33339118122` | ✅ |
| S2 | `OutboxDeliveryUseCase` claim/backoff/mark (§5.1, §5.2) | `OutboxDeliveryUseCaseTest` (claim filters, batch cap, skip-locked fake, backoff schedule) | #33 `33340707434` | ✅ |
| S3 | `SnsEventPublisher` + compose LocalStack + init script (§4, §5.2) | Bean wiring; `deploy/localstack-init.sh` idempotent; `.env.example` rows (§4.1 all 10) | #35 `33344724604` / #36 `33345869248` / #37 `33346145132` | ✅ |
| S4 | Relay ITs 1–3: publish, retry, SKIP LOCKED race (§6, §7) | `OutboxRelayIT` (publish_pending…, publish_failure…, concurrent_runOnce…), zero sleeps | #38 `33348323823` | ✅ |
| S5 | Retention purge + BoE sizing doc (§5.4, §5.7) | `OutboxRelayIT.purge_deletes_old_sent_keeps_fresh_sent_and_pending`; `docs/load-test-baseline.md` §"Back-of-envelope" | #40 `33349152979` | ✅ |
| S6 | IT5 **M2 anchor E2E** + IT6 topology (§7) | `OutboxDeliveryE2EIT.confirmed_payment_reaches_the_fifo_queue_with_group_and_dedup_ids` (API create → webhook confirm → `runOnce()` → `payment.confirmed` on queue, MessageGroupId=txid, MessageDeduplicationId=eventId); `AwsTopologyIT` (FifoQueue both, redrive maxReceiveCount=5, subscription) | #42 `33353813310` **RED** → #43 `33354167958` **GREEN** | ✅ |
| S6 fix | Full E3 §5.6 envelope in writers (owner decision; §5.3 wire format) | `CreatePaymentUseCaseTest.outbox_column_carries_full_envelope_with_nested_payload_and_request_id` + `OutboxDeliveryE2EIT` envelope asserts (eventId v4, version, aggregateId, nested payload) | #43 `33354167958` | ✅ |
| S6 fix | LocalStack ITs static test credentials (was: ambient chain, red on CI) | Same ITs re-run | #43 `33354167958` | ✅ |
| S6 fix | `SimulatorChargeAdapter` proxy-poisoning fix (`System.setProperty("http.proxy*","")` broke AWS `UrlConnectionHttpClient` in same JVM) | `OutboxDeliveryE2EIT` SNS publish path unblocked; `SimulatorChargeAdapterWireMockIT` | #43 `33354167958` | ✅ |
| S7 | Matrix + README/CHANGELOG truth-scoped + ledger flip (LAST) | This file; `docs/epics.md` E6 ✅ with final run id at flip HEAD | #44 `33354450665` + final flip run | ✅ |

---

## Testing Requirements Coverage (spec §7)

| Requirement | Test (CI-verified) | Run pair | Status |
|---|---|---|---|
| Unit: claim due filter, batch cap | `OutboxDeliveryUseCaseTest.claim_filters_by_due_and_status` / `.batch_size_cap_respected` | #33 `33340707434` | ✅ |
| Unit: skip-locked semantics | `OutboxDeliveryUseCaseTest.skip_locked_semantics_via_fake` | #33 `33340707434` | ✅ |
| Unit: backoff schedule (30s/2min/5min cap) | `OutboxDeliveryUseCaseTest.backoff_schedule` | #33 `33340707434` | ✅ |
| Unit: mark semantics conditional on PENDING | `OutboxDeliveryUseCaseTest` mark paths (via fake store asserts) | #33 `33340707434` | ✅ |
| Unit: purge cutoff with injected Clock | `OutboxRelayIT.purge_deletes_old_sent_keeps_fresh_sent_and_pending` + `OutboxDeliveryUseCase` purge unit paths | #40 `33349152979` | ✅ |
| Unit: `OutboxId` v7 properties | `OutboxIdTest` (4 tests incl. monotonic_under_fixed_clock, 10k uniqueness) | #32 `33339118122` | ✅ |
| Unit: eventId parse failure path (writer bug) | `OutboxDeliveryUseCaseTest` defect path (row left, ERROR logged) | #33 `33340707434` | ✅ |
| IT1: happy publish, body = seeded jsonb verbatim, group/dedup ids, SENT + published_at | `OutboxRelayIT.publish_pending_outbox_row_to_queue_with_metadata_and_mark_SENT` | #38 `33348323823` | ✅ |
| IT2: retry deferral (attempt_count=1, next_attempt_at ≈ +30s, PENDING) | `OutboxRelayIT.publish_failure_bumps_attempt_and_defers_next_attempt` | #38 `33348323823` | ✅ |
| IT3: two-thread SKIP LOCKED race, no loss/no double-SENT | `OutboxRelayIT.concurrent_runOnce_workers_yield_no_loss_and_no_double_SENT` | #38 `33348323823` | ✅ |
| IT4: purge (old SENT deleted; fresh SENT + PENDING kept) | `OutboxRelayIT.purge_deletes_old_sent_keeps_fresh_sent_and_pending` | #40 `33349152979` | ✅ |
| IT5: M2 anchor E2E (create → webhook → runOnce → queue message) | `OutboxDeliveryE2EIT.confirmed_payment_reaches_the_fifo_queue_with_group_and_dedup_ids` | #43 `33354167958` | ✅ |
| IT6: topology attrs (FifoQueue, redrive maxReceiveCount=5, subscription) | `AwsTopologyIT` (all asserts via `GetQueueAttributes`) | #43 `33354167958` | ✅ |

## Concurrency & Race Proofs (spec §6)

| Race | Proof | Run pair | Status |
|---|---|---|---|
| Two workers claim same row | IT3 (threads + shared seeds → each row exactly one SENT) | #38 `33348323823` | ✅ |
| Crash between publish and mark | At-least-once + FIFO dedup contract asserted (MessageDeduplicationId=eventId in IT1/IT5) | #38 `33348323823` / #43 `33354167958` | ✅ |
| Purge vs relay | Disjoint statuses (SENT vs PENDING); IT4 asserts PENDING untouched | #40 `33349152979` | ✅ |
| SNS down | IT2 (broken ARN → backoff deferral, PENDING) | #38 `33348323823` | ✅ |
| Row without eventId | Unit defect path (leave + ERROR, never dropped) | #33 `33340707434` | ✅ |

## Delivery Guarantee Statement (spec §5.6 — verbatim)

At-least-once, per-payment FIFO ordering, dedup by `MessageDeduplicationId=eventId` (5-min FIFO window),
consumer idempotency by `eventId` = E10's binding contract. Nobody in this repo ever writes "exactly once".

---

## Hygiene Gates (spec §7 — greps run at `b667857`)

| Gate | Command | Result | Status |
|---|---|---|---|
| No Spring Cloud / Kafka | `grep -rn "com.springframework.cloud\|kafka" --include="*.java" modules/ apps/ \| grep -v target` | 0 hits | ✅ |
| AWS SDK confined to `adapter/out/messaging/` | `grep -rln "software.amazon.awssdk" modules/*/src/main apps/api/src/main \| grep -v adapter/out/messaging` | 0 hits (prod); `SnsEventPublisher` is the sole prod import site | ✅ |
| No `Thread.sleep` in E6 code | grep on `OutboxDeliveryUseCase`, `SnsEventPublisher`, `EventEnvelopeFactory`, `JdbcOutbox*` | 0 hits. Pre-existing, outside E6: `SimulatorChargeAdapter.sleep()` (E3 PSP retry sleeper, injectable — E6 scope rule targets relay/tests); `PspStub` latency knobs in E3/E4 ITs | ✅ |
| No `String.format` JSON | grep on prod sources | 0 hits in E6/JSON paths. Pre-existing EMV/hex formatting in `BrCode`/`ApiKeyHasher`/`WebhookSignatureValidator` (not JSON) | ✅ |
| No `Instant.now()` in request paths | grep on E6 files | 0 hits — injected `Clock` everywhere (`clock.instant()`) | ✅ |
| Env table complete | `.env.example` | All 10 rows from §4.1 (8 `DARGENT_*` + `AWS_REGION`/`AWS_ENDPOINT_URL`); `PSP_*`/`CHAOS_*` untouched | ✅ |
| Scope diff = 0 | `git diff 3b60ba8..HEAD --stat -- modules/ledger modules/notifications apps/psp-simulator` | 0 lines | ✅ |

---

## Evidence Requirements

| Requirement | Status |
|---|---|
| Every cell cites CI test name + run number AND id (API-verified pairs) | ✅ |
| Zero pending cells | ✅ |
| `mvn -B verify` green on `main` | ✅ (run #43 `33354167958` at `b04b889`; #44 `33354450665` at `6330070`) |
| Matrix zero pending | ✅ (this file) |
| Ledger E6 flip is the LAST commit, final run id cited at that HEAD | ✅ (flip `084bb3a`, run #47 `33355346290` green) |

## Run IDs Summary (canonical pairs from `tasks/e3r-block1-verification.md`)

| Run | Id | Head | Meaning |
|---|---|---|---|
| #31 | `33336657975` | `63e10cb` | E6 S0 (TD-13 correction) |
| #32 | `33339118122` | `4fcd51f` | E6 S1 (OutboxId UUIDv7) |
| #33 | `33340707434` | `6a04323` | E6 S2 (delivery use case) |
| #35 | `33344724604` | `1193f36` | E6 S3 |
| #36 | `33345869248` | `b819acc` | E6 S3 fix |
| #37 | `33346145132` | `2caf603` | E6 S3 docs |
| #38 | `33348323823` | `1d1e237` | E6 S4 fix+test (relay corrections + relay ITs, 3/3 green) |
| #39 | `33348582429` | `c199da6` | docs: canonical table S4 pair |
| #40 | `33349152979` | `4264cd0` | E6 S5 (retention purge + BoE, RelayIT 4/4 green) |
| #41 | `33349330604` | `9c84de7` | docs: canonical table S5 pair |
| #42 | `33353813310` | `d3d5590` | E6 S6 — **RED**: LocalStack ITs used ambient credentials chain |
| #43 | `33354167958` | `b04b889` | E6 S6 fix: static test credentials — **GREEN** (S6 canonical pair) |
| #44 | `33354450665` | `6330070` | docs: canonical table S6 pairs |
| #46 | `33355073316` | `0c7ab78` | E6 S7 closure docs (matrix + README/CHANGELOG) |
| #47 | `33355346290` | `084bb3a` | **E6 ledger flip** — final run id at flip HEAD; epic closed |

All cells green. All runs green. All evidence cited. E6 complete — **E5 unblocked; E7 (ledger) unblocked by E6.**
