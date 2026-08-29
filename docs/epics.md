# Epics — Dargent

**Canonical epic ledger** (single source of truth — consolidates the earlier `tasks/epics.md` and the first
`docs/epics.md`; that earlier mapping is superseded, including its numbering). Each epic has its spec + backlog +
implementation sequence + acceptance matrix in `tasks/`. An epic closes when its milestone meets the
Definition of Done (AGENTS.md §6) and its matrix has zero `pending` cells.

Status: ☐ open · ◐ in progress / spec published · ✅ done (evidenced)

---

## Priority order (dependency-driven)

Ordering obeys two rules: **dependency first** (an epic starts when its dependencies are green), **value
second** (among unblocked epics, the one that unlocks the most goes first).

| # | Epic | Module(s) | Depends on | Milestone | Status |
|---|---|---|---|---|---|
| E0 | Foundations & skeleton | all | — | M0 | ✅ 2026-08-28 — CI green (run #33217044326), matrix evidenced |
| E1 | Payment domain & state machine | payments | E0 | M1 | ✅ 2026-08-29 — CI green (run #33225043138), matrix evidenced, lesson #12 |
| E2 | PSP simulator API (cobs + payer bank + chaos) | psp-simulator | E0 *(parallel with E1)* | M1 | ◐ spec set published (`tasks/psp-simulator-e2-*`) — implementation next |
| E3 | Create payment: idempotency + API keys + error contract | payments, api | E1, E2 | M1 | ☐ |
| E4 | Webhook intake: HMAC, anti-replay, dedupe, confirmation | payments, api | E1, E2 | M1 | ☐ |
| E5 | Expiration, resurrection & reconciliation | payments | E3, E4 | M3 | ☐ |
| E6 | Outbox + messaging backbone (relay, SNS/SQS, DLQ) | payments, api | E3 | M2 | ☐ |
| E7 | Ledger core: double entry, projection, balance proof, settlement | ledger | E6 | M2 | ☐ |
| E8 | Refunds: partial/total, fee reversal, balance drain | payments, ledger, api | E4, E7 | M3 | ☐ |
| E9 | Delivery hardening: backoff, EXHAUSTED, requeue, republish | payments, api | E6, E7 | M3 | ☐ |
| E10 | Notifications consumer | notifications | E6 | M2 | ☐ |
| E11 | Observability: metrics, JSON logs, correlation, lockdown IT | api (cross-cutting) | E0 | M4 | ☐ |
| E12 | Blue-green deploy & runtime smoke | deploy/, api | E0 | M4 | ☐ |
| E13 | Full quality & security gates | CI | E0 | M4 | ☐ |
| E14 | Release engineering & restore drill | repo, docs | E12, E13 | M4 | ☐ |
| E15 | Stretch batch: card Strategy · k6 gate · Redis cache · webhook reprocessing | payments, api | E8 (card: E1, E2) | M5 | ☐ |

## Artifact index

| Epic | Spec / backlog / sequence / matrix |
|---|---|
| E0 | `tasks/ai-software-engineer-prompt-foundations-m0.md` · `tasks/foundations-m0-{spec,backlog,implementation-sequence}.md` · `tasks/m0-acceptance-matrix.md` |
| E1 | `tasks/ai-software-engineer-prompt-payment-domain-e1.md` · `tasks/payment-domain-e1-{spec,backlog,implementation-sequence}.md` · `tasks/e1-acceptance-matrix.md` |
| E2 | `tasks/ai-software-engineer-prompt-psp-simulator-e2.md` · `tasks/psp-simulator-e2-{spec,backlog,implementation-sequence}.md` · `tasks/e2-acceptance-matrix.md` |

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

### E2 — PSP simulator API ◐ (spec published — in implementation)
`POST /cobs` (merchant-owned txid, PIX profile fields for the API's BR Code composer), `GET /cobs/{txid}`
(reconciler's truth endpoint for E5), `POST /cobs/{txid}/payments` (payer bank rules: expiry → `409`,
double-pay → `409`), HMAC-SHA256 signed webhooks with the **shared test vector binding for E4's validator**,
async single-attempt delivery (recovery is E5's reconciler, not retries), five deterministic chaos knobs.
**Proves:** endpoint ITs, wire-level signature IT against a stub receiver, duplicate/drop/delay behavior tests.

### E3 — Create payment
Use case persisting `PENDING` + idempotency row + outbox row in one transaction; PSP call retryable (D19);
`Idempotency-Key` semantics (409 conflict / 425 in-flight + Retry-After / snapshot replay); Stripe-style API
keys (SHA-256 at rest); canonical `ErrorResponseWriter` + problem+json catalog; BR Code generation
(EMV + CRC16) from the simulator's PIX fields; `GET /payments/{txid}` + cursor-paginated listing.
**Proves:** playbook scenarios 1–4, 15, 25; the getting-started curl stops being documentation.

### E4 — Webhook intake
Fail-closed HMAC filter (timestamp + rawBody, 5-min anti-replay — validates against E2's contract and
vector), raw persistence even on invalid signatures (attack audit), `provider_event_id` dedupe,
conditional-UPDATE confirmation with fee breakdown. **Proves:** scenarios 6–8.

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

- **Track A (core path):** E1 → E3 → E4 → E5 — the money lifecycle.
- **Track B (outside world):** E2 — alongside E1; unblocks E3/E4. *(current track)*
- **Track C (events spine):** E6 → E7 → E9 — starts as soon as E3 exists.
- **Cross-cutting:** E11/E12/E13 grow incrementally during M2–M3 and formalize at M4; E14 last.

---

> Conventions: `✅` = epic DoD met, matrix zero `pending`, evidence recorded · `◐` = spec published and/or
> implementation underway · `⏳/☐` = not started. Closing an epic requires: green CI on `main`, matrix
> filled, docs synced, epics row flipped **in the same change set**.
