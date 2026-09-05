# Epics — Dargent

**Canonical epic ledger** (single source of truth — consolidates the earlier `tasks/epics.md` and the first
`docs/epics.md`; that earlier mapping is superseded, including its numbering). Each epic has its spec + backlog +
implementation sequence + acceptance matrix in `tasks/`. An epic closes when its milestone meets the
Definition of Done (AGENTS.md §6) and its matrix has zero `pending` cells.

Status: ☐ open · ◐ in progress / spec published · ◐ **reopened** = documented as closed but refuted in code
(2nd external audit, 2026-08-29 — remediated via E3R) · ✅ done (evidenced)

> **Correction note (2026-08-30, E3R closed):** this ledger previously showed E3 ✅ ("commit a979c80, 73 tests pass")
> and E4 ✅ ("run #33267438415, full loop proven"). Both closures were refuted by the 2nd external audit: the
> create endpoint never existed over HTTP, the use case violates its own spec, the scenario IT shipped disabled,
> and `POST /webhooks/psp` was never implemented. The prior rows were fabricated evidence; the E4 acceptance
> matrix committed in `97882494` cites test classes that do not exist in this repository. See
> `tasks/create-webhook-remediation-e3r-spec.md` §2 (defect register). **E3R closed: run #30 (33333739409) green — all E3/E4/E3R cells green.**

---

## Priority order (dependency-driven)

Ordering obeys two rules: **dependency first** (an epic starts when its dependencies are green), **value
second** (among unblocked epics, the one that unlocks the most goes first).

| # | Epic | Module(s) | Depends on | Milestone | Status |
|---|---|---|---|---|---|
| E0 | Foundations & skeleton | all | — | M0 | ✅ 2026-08-28 — CI green (run #33217044326), matrix evidenced |
| E1 | Payment domain & state machine | payments | E0 | M1 | ✅ 2026-08-29 — CI green (run #33225043138), matrix evidenced, lesson #12 |
| E2 | PSP simulator API (cobs + payer bank + chaos) | psp-simulator | E0 *(parallel with E1)* | M1 | ✅ 2026-08-29 — matrix evidenced (`tasks/e2-acceptance-matrix.md`), spec §5.4 vector asserted |
| E3 | Create payment: idempotency + API keys + error contract | payments, api | E1, E2 | M1 | ✅ 2026-08-30 — run #19 `33285295818` (create path), run #20 `33288538459` (scenarios), run #30 `33333739409` — E3R remediation complete |
| E4 | Webhook intake: HMAC, anti-replay, dedupe, confirmation | payments, api | E1, E2 | M1 | ✅ 2026-08-30 — run #24 `33318535724` (scenarios 6,7,8,10 + 3 ignored + full loop), run #25 `33321575303` (BD-13/BD-11), run #26 `33326648770` (BD-12/13 partial + sentinel), run #27 `33328906357` (BD-14), run #29 `33331033505` (BD-11 failure-injection), run #30 `33333739409` — E3R complete |
| E3R | Remediation: create path + webhook intake (audit pass) | payments, api | E1, E2 (remediates E3 + E4) | M1 | ✅ 2026-08-30 — run #30 `33333739409` green — all E3/E4/E3R cells green — **unblocks E5 and E6** |
| E5 | Expiration, resurrection & reconciliation | payments | E3R (E3+E4 remediated) | M3 | ✅ 2026-09-02 — runs #125 `33693408878` (DEBT-1), #126 `33694469538` (V111), #127 `33700561182` (expiration), #128 `33706674658` (reconciler sc.26), #129 `33707174938` (resurrection sc.11/27), #130 `33709904795` (give-up S5), #131 `33710432248` (scenarios 9/10 S6), #132 `33711320405` (DEBT-4 auditor S7), #134 `33711990378` (docs flip S8) — matrix evidenced (`tasks/expiration-reconciliation-e5-spec.md` §10) — **DEBT-1 closed, DEBT-4 closed; M3 remains open for E8+E9**; citation #136 (unregistered per #57/#67 precedent) |
| E6 | Outbox + messaging backbone (relay, SNS/SQS, DLQ) | payments, api | E3R (E3 remediated) | M2 | ✅ 2026-08-30 — run #43 `33354167958` (S6 canonical: envelope + IT5 M2 anchor + IT6) → #46 `33355073316` (closure docs green) — matrix evidenced (`tasks/e6-acceptance-matrix.md`) — **unblocks E5, E7, E10** |
| E7 | Ledger core: double entry, projection, balance proof, settlement | ledger | E6 | M2 | ✅ 2026-08-31 — runs #53 `33443733757` (S1 schema + migration), #54 `33448005815` (S3 consumer + topology), #56 `33454526460` (S4 settlement/proof/rebuild/read API), #59 `33462467004` (S5 ITs IT1–IT6 + §7.1 wire-format + prod fixes), #62 `33464758612` (S6 BoE + docs), #65 `33465919415` (S7 hygiene) — matrix evidenced (`tasks/e7-acceptance-matrix.md`) — **M2 ✅ (closed with E10, 2026-09-02)** |
| E8 | Refunds: partial/total, fee reversal, balance drain | payments, ledger, api | E4, E7 | M3 | ✅ 2026-09-03 — Block 1 (S1 refund use case, S2 endpoint, S3 ledger `refund.created` golden vector `RefundFlowIT`, HEAD `8bad5e8`/CI `33827336501`) → Block 2 (S6: `RefundBalanceGuardIT` 2/2 + `RefundRaceIT` 2/2 for scenarios 12 & 23, DB-arbitrated drain, `refund_skipped_balance` audit + skip-audit actor fix `369b0c6`; S7: auditor refund legs (c)/(d) + connection-leak fix `JournalCoverageAuditorIT` 6/6 `7993f1f`; S8: docs + matrix `c8af90b`) — HEAD `c8af90b` / CI `33831934579` green — matrix evidenced (`tasks/refunds-e8-spec.md` §10) — **M3 ✅ (E9 delivery hardening complete)** |
| E9 | Delivery hardening: backoff, EXHAUSTED, requeue, republish | payments, api | E6, E7 | M3 | ✅ 2026-09-04 — run #155 `33921797910` (head `df06c9c`) (S1 `OutboxExhaustionIT` 1/1, S2 `OutboxRequeueIT` 6/6 + `OutboxAdminRotationIT` 2/2 Q11 403 leg, S3 `OutboxRepublishIT` 10/10 + `OutboxRepublishRotationIT` 3/3, S4 `OutboxRepublishIT.republish_re_run_produces_identical_new_ids` deterministic dedupe, S5 `docs/runbooks/dlq-inspection.md`, S6 `design.md` §13 M3✅ + `CHANGELOG` 1.0.3 + TD-26 annotations) — all E9 ITs green (23 tests, 0 failures) — matrix evidenced (`tasks/delivery-hardening-e9-spec.md` §10) — **M3 ✅** |
| E10 | Notifications consumer | notifications | E6 | M2 | ✅ 2026-09-02 — Block 1 audited (S1–S3 + riders), Block 1.5 remediation (#85–#116: 27 cited reds → #113 `33674334484` Instant binding, #114 `33675295464` poison IT, #115 `33676638904` BD-18, #116 `33677327831` TD-16 correction), Block 2 #118 `33683261976` (S6 read API) → #119 `33684112090` (flip: README/CHANGELOG/matrix) → #120 `33684616551` (citation; run unregistered per #57/#67 precedent) — TD-20 rider pending (register); matrix evidenced (`tasks/notifications-e10-spec.md` §10) — **closes M2** |
| E11 | Observability: metrics, JSON logs, correlation, lockdown IT | api (cross-cutting) | E0 | M4 | ✅ 2026-09-05 — run #160 `33942554113` (head `8da0fc8`; tree carries S0–S3: `ManagementPortIT` 3/3, `JsonLogCorrelationIT` 3/3, `OnCallTxidDrillIT` 1/1, `ProductionLockdownIT` 6/6), run #161 `33945666982` (head `d7eade9`, S1 wire-correlation remediation — intake/relay/ingest ECS lines), run #162 `33987573655` (head `a98a8d0`, S4 `MetricsScrapeIT` 1/1 — 8 frozen series + latent `SqsClient` bean-ambiguity fix) — matrix evidenced (`tasks/observability-e11-spec.md` §10) — **M4 ◐ (completes with E12+E13)** |
| E12 | Blue-green deploy & runtime smoke | deploy/, api | E0 | M4 | ☐ |
| E13 | Full quality & security gates | CI | E0 | M4 | ☐ |
| E14 | Release engineering & restore drill | repo, docs | E12, E13 | M4 | ☐ |
| E15 | Stretch batch: card Strategy · k6 gate · Redis cache · webhook reprocessing | payments, api | E8 (card: E1, E2) | M5 | ☐ |

## Artifact index

| Epic | Spec / backlog / sequence / matrix |
|---|---|
| E0 | `tasks/ai-software-engineer-prompt-foundations-m0.md` · `tasks/foundations-m0-{spec,backlog,implementation-sequence}.md` · `tasks/m0-acceptance-matrix.md` |
| E1 | `tasks/ai-software-engineer-prompt-payment-domain-e1.md` · `tasks/payment-domain-e1-{spec,backlog,implementation-sequence}.md` · `tasks/e1-acceptance-matrix.md` *(matrix file not yet committed — TD-6)* |
| E2 | `tasks/ai-software-engineer-prompt-psp-simulator-e2.md` · `tasks/psp-simulator-e2-{spec,backlog,implementation-sequence}.md` · `tasks/e2-acceptance-matrix.md` *(matrix file not yet committed — TD-6)* |
| E3 | `tasks/ai-software-engineer-prompt-create-payment-e3.md` · `tasks/create-payment-e3-{spec,backlog,implementation-sequence}.md` · `tasks/e3-acceptance-matrix.md` |
| E3.5 | `tasks/ai-software-engineer-prompt-repo-hardening-e35.md` · `tasks/repo-hardening-e35-{spec,backlog,implementation-sequence}.md` · `tasks/e35-acceptance-matrix.md` |
| E4 | `tasks/ai-software-engineer-prompt-webhook-intake-e4.md` *(superseded by E3R)* · `tasks/webhook-intake-e4-{spec,backlog,implementation-sequence}.md` · `tasks/e4-acceptance-matrix.md` (**VOID — fabricated; rebuilt by E3R R7**) |
| E3R | `tasks/ai-software-engineer-prompt-create-webhook-remediation-e3r.md` · `tasks/create-webhook-remediation-e3r-{spec,backlog,implementation-sequence}.md` · `tasks/e3r-acceptance-matrix.md` |
| E6 | `tasks/outbox-messaging-e6-spec.md` · `tasks/outbox-messaging-e6-{backlog,implementation-sequence}.md` · `tasks/e6-acceptance-matrix.md` |

## Dependency graph

```mermaid
graph TD
    E0["E0 Foundations"] --> E1["E1 Payment domain"]
    E0 --> E2["E2 PSP simulator"]
    E1 --> E3["E3 Create payment"]
    E2 --> E3
    E1 --> E4["E4 Webhook intake"]
    E2 --> E4
    E3 --> E5["E5 Expiration · reconciliation"]
    E4 --> E5
    E3 --> E6["E6 Outbox + messaging"]
    E6 --> E7["E7 Ledger core"]
    E4 --> E8["E8 Refunds"]
    E7 --> E8
    E6 --> E9["E9 Delivery hardening"]
    E7 --> E9
    E6 --> E10["E10 Notifications"]
    E0 --> E11["E11 Observability"]
    E0 --> E12["E12 Blue-green deploy"]
    E0 --> E13["E13 Quality & security gates"]
    E12 --> E14["E14 Release + restore drill"]
    E13 --> E14
    E3R["E3R Remediation (E3+E4)"] --> E5
    E3R --> E6
```

---

## Epic briefs & acceptance anchors

### E0 — Foundations & skeleton ✅
Multi-module Maven, ArchUnit + boundary script (prod-only scan — lessons #11), compose topology, CI
build/image gates with non-root check, per-module Flyway. **Closed:** all 8 matrix criteria evidenced.

### E1 — Payment domain & state machine ✅
Rich `Payment` entity (zero setters, injected time, drainable domain events), forward-only transitions with
`EXPIRED`-non-terminal resurrection, VOs (`Txid`, `EndToEndId`, `BpsRate`, `FeeBreakdown`), repository port
with lost-race semantics (fake + JPA on one contract suite), V102, conditional-UPDATE persistence seam.
**Proved:** transition-table coverage, fee property tests, `PaymentJpaAdapterIT` on real PostgreSQL 16,
`PaymentConcurrentTransitionIT` (8 threads → exactly one winner). Lesson #12: flush-catch marks the tx
rollback-only — conditional UPDATE is the only clean lost-race arbitration.

### E2 — PSP simulator API ✅ (2026-08-29)

The honest "outside world" (AGENTS.md §2): `POST /cobs` (merchant-owned txid, PIX profile fields for the
API's BR Code composer), `GET /cobs/{txid}` (reconciler's truth endpoint for E5), `POST /cobs/{txid}/payments`
(payer bank rules: expiry → `409`, double-pay → `409`), HMAC-SHA256 signed webhooks with the **shared test
vector binding for E4's validator** (`WebhookSignerTest` asserts §5.4 verbatim), async single-attempt
delivery (recovery is E5's reconciler, not retries), six deterministic chaos knobs (duplicate, delay, drop,
error-rate, latency, seed) proven with forced modes. **Proves:** endpoint ITs, wire-level signature IT
against a test-local stub receiver (recompute over captured bytes+timestamp), duplicate/drop/delay
behavior tests at both dispatcher and endpoint level. Evidence: `tasks/e2-acceptance-matrix.md`.

### E3 — Create payment ✅ (2026-08-30, E3R closed)
The 2nd external audit (2026-08-29) refuted the prior closure: the endpoint never existed over HTTP
(`PaymentController` ships GETs only), the use case violated E3 spec §5.7/§5.8 (ten audited defects), and the
proving IT shipped `.disabled`. The E3 spec remained the binding behavior contract; remediation was E3R.
**E3R closed (run #30 `33333739409`):** `POST /v1/payments` live with idempotency, API keys, canonical errors,
dynamic BR Code; `CreatePaymentUseCase` with `TransactionTemplate` core + explicit PSP seam; idempotency PK race
→ 425, replay 201, conflict 409; D19 retry + 409 read-back; exhaustion → `FAILED` + 502 `psp_unavailable` +
`PaymentFailed` outbox row + idempotency key deleted; dynamic BR Code (golden vector `EDD2`); outbox `payment.created`
envelope + shared serializer; audit trail with `actor_key_id = apiKeyId`; `SecurityConfig` single source of truth;
`ConfigValidator` fail-fast; reads GET detail + cursor pagination; cross-tenant → 404; scenario ITs 1-4, 15, 25
all green (runs #19 #22 #28).

### E4 — Webhook intake ✅ (2026-08-30, E3R closed)
Refuted by the audit: only `V108` + the `WebhookEventStore` port/adapter existed (`47d24408`); validator, intake
use case and `POST /webhooks/psp` were absent; the closure matrix committed in `97882494` cited non-existent
test classes and was void. **E3R closed (run #30 `33333739409`):** `POST /webhooks/psp` live with fail-closed
HMAC-SHA256 over `timestamp + "." + rawBody` (byte-exact vector §5.4), anti-replay (5 min, injected Clock),
dedupe (`provider_event_id = endToEndId|type`), conditional confirm (fee=100bps, `confirm_from_webhook`
audit with sentinel actor `00000000-0000-0000-0000-000000000000`, outbox `payment.confirmed` {amount, fee, net,
late:false}). Scenarios 6,7,8,10 + 3× ignored + full-loop all green (runs #24 #25 #26 #27 #28). BD-12 audit
sentinel, BD-13 `paidAt` guarded, BD-11 atomicity failure-injection IT + happy-path, BD-14 sentinel ratified.

### E3R — Remediation: create path + webhook intake ✅ (2026-08-30)
Opened by the 2nd external audit (2026-08-29). Restored the documented surface for real: re-enabled the disabled
scenario IT (red first — the debt made visible), fixed the create use case against E3 §5.7/§5.8 (transactional
core, canonical `PENDING`, PSP truth via conditional UPDATE on the re-read aggregate, D19 + read-back, real
snapshot/requestId/actor, shared serializer, config callback), landed `POST /v1/payments`, implemented webhook
intake per E4 §5.1–§5.4 (fail-closed HMAC with byte-exact vectors, anti-replay, dedupe, conditional confirmation,
full-loop IT), deleted the debug tests, re-evidenced every matrix cell with CI tests (name + run id), and
installed the governance (AGENTS §5.5/§5.6, commit-msg = diff, DEBT-3, lesson #14: green CI proves tests pass —
not that they are right, nor that the code exists). **Closed: run #30 `33333739409` green — all E3/E4/E3R cells green. Unblocks E5 and E6.**

### E5 — Expiration, resurrection & reconciliation
Expiration scheduler (partial index `WHERE status='PENDING'`, conditional UPDATE — design.md §5.1);
late confirmation resurrects with `late=true` + audit; reconciler polls `GET /cob` and confirms on its own.
**Proves:** scenarios 9–11, 26–27 — the soul of the project.

### E6 — Outbox + messaging backbone
Envelope + `EventPublisher` port, SNS FIFO topic + per-consumer SQS FIFO + DLQs provisioned at boot (AWS SDK
v2 channel adapters), relay with `FOR UPDATE SKIP LOCKED` + N workers; broker-behavior proof ITs come first
(lessons #4). Aggregate events from E1 surface as `payment.*` envelopes. **Proves:** scenarios 14, 16–17.

### E7 — Ledger core
`accounts` chart, append-only `journal_entries`/`ledger_entries` (no UPDATE/DELETE grants), idempotent
consumer (`event_id` unique), transactional `balances` projection, daily proof job, D+1 settlement.
`refunded_cents` in E1 is aggregate-tracked; the ledger becomes the truth here. **Proves:** scenarios 21–22
(jqwik: projection == SUM), 24.

### E8 — Refunds
Partial/total under pessimistic payment lock (`SELECT FOR UPDATE`, rides on E1's `refund()` transition),
proportional fee reversal (D8), ledger entries [3]+[4], REST endpoint, drain of `AVAILABLE`.
**Proves:** scenarios 12, 19, 23 (concurrent refunds, balance guard).
**As-built:** the refund is serialized twice — the payments lock/version guard (one `201`, loser `409
refund_exceeds_remaining`) and the ledger conditional drain `WHERE balance_cents >= :drain` (one POSTED,
loser IGNORED with `refund_skipped_balance` audit). Auditor covers refund-vs-POSTED (legs c/d).

### E9 — Delivery hardening
Backoff 30s→2min→5min, `FAILED`→`EXHAUSTED`, audited requeue endpoint, outbox republish tool (our replay),
DLQ inspection recipes. **Proves:** scenarios 18–20.

### E10 — Notifications consumer
Consumes the event bus, records notifications exactly once (dedupe), nothing more — the module stays boring
by design. **Proves:** consumer side of scenario 17.

### E11 — Observability
`dargent_*` metric families (outbox lag, DLQ depth, reconciler confirmations…), request-correlation filter
(MDC + `X-Request-Id` echo), structured JSON logs (Boot 4 ECS), health model gated on Postgres + LocalStack,
**production lockdown IT**. **Proves:** observability.md §3–4; lockdown = scenario 28.

### E12 — Blue-green deploy & runtime smoke
`deploy.sh` (readiness gate → 10%/30s canary → cutover, auto-abort), `rollback.sh`, nginx runtime-conf flip
(`down`, not `weight=0` — lessons #9/#10), `shutdown-under-load` test gating CI. **Proves:**
release-runbook §3–5 exercised with recorded evidence.

### E13 — Full quality & security gates
SpotBugs, OWASP (NVD cache), JaCoCo per-module floors measured post-IT, Trivy 2-pass, SBOM CycloneDX,
CodeQL, Dependency Review, third-party actions SHA-pinned. **Proves:** design.md §11.1 pipeline complete.

### E14 — Release engineering & restore drill
Annotated tag → semver image + GitHub Release (jar + SBOM of the exact image); restore drill with evidence
in `docs/drills/`; runbook validated end to end. **Proves:** release-runbook §2, §6 — closes v1.0.0.

### E15 — Stretch batch
Card as second `PaymentMethod` Strategy (must not touch the PIX domain — abstraction proof), k6 promoted to
hard gate after calibration, Redis read cache, admin webhook reprocessing. Independent opt-ins.

---

## Parallelization map (solo-dev friendly)

- **Track A (core path):** E1 → E3R → E5 — the money lifecycle (E3/E4 reopened; remediated by E3R).
- **Track B (outside world):** E2 — alongside E1; unblocks E3/E4.
- **Track C (events spine):** E6 → E7 → E9 — starts when E3R closes.
- **Cross-cutting:** E11/E12/E13 grow incrementally during M2–M3 and formalize at M4; E14 last.

---

> Conventions: `✅` = epic DoD met, matrix zero `pending`, evidence recorded (CI test + run id) · `◐` = spec
> published and/or implementation underway · `◐ reopened` = closure refuted by audit; remediation required ·
> `⏳/☐` = not started. Closing an epic requires: green CI on `main`, matrix filled with CI-test evidence,
> docs synced, epics row flipped **in the same change set** — and the commit message describes exactly its diff.
