# Payment Processing & Transaction System — Design Document

| | |
|---|---|
| **Version** | 1.0.2 |
| **Date** | 2026-08-28 |
| **Status** | Approved — consolidation of the brainstorm (16 debate rounds) |
| **Name** | **Dargent** — French *d'argent*, "of silver / of money" (Latin *argentum*). Zero direct collisions; documented search-adjacency caveat with the "Argent" namespace (D22). History: codename "Cobre" retired for colliding with a homonymous LatAm fintech in the same domain |
| **Objective** | Portfolio project: payment-infrastructure backend (Stripe/Razorpay-style PSP) demonstrating distributed systems engineering, financial consistency and on-premises operations |

> **Living document.** Single source of truth. Design changes are reviewed here and bump the version (history in Appendix C). The original approved pt-BR snapshot is archived at [`design-ptbr.md`](design-ptbr.md).

---

## 1. Executive Summary

A backend implementing the **complete payment lifecycle**: create → process → verify → webhook → success/failure → refund — using **PIX with dynamic QR codes**, mediated by a **simulated PSP**.

The value is not the payment CRUD — it is the guarantees:

- **End-to-end idempotency** (merchant request, PSP webhook, queue message)
- **State machine** imposed by the database (conditional UPDATE), race-immune
- **Transactional outbox** → SNS/SQS FIFO (LocalStack) → idempotent consumers
- **Append-only double-entry ledger** with a balance proof
- **Reconciliation** against the PSP when the webhook never arrives
- **Real on-premises operations**: Docker Compose + NGINX blue-green with canary, CI with security gates

### 1.1 Goals

| Goal | Proven by |
|---|---|
| No payment is ever charged twice, under any race | Concurrency tests with `CyclicBarrier` + idempotency keys |
| No confirmed payment is lost — even without a webhook | Chaos scenario: suppressed webhook → reconciler confirms (runs in CI) |
| Every cent traceable and balanced | Double-entry ledger + balance-proof job + property tests (jqwik) |
| Zero-downtime deploys on bare metal | NGINX blue-green with canary, instant rollback, shutdown-under-load CI gate |
| Auditable quality | Acceptance matrix per milestone + CI security gates |

### 1.2 Non-goals (v1.0)

| Out of scope | Reason |
|---|---|
| Cloud / k8s | Decision: bare metal on-premises with Docker Compose |
| Credit cards in the core | Stretch goal (proves the Strategy abstraction at the end) |
| Static QR (P2P) | Complicates reconciliation, adds nothing; dynamic QR only |
| Payouts (withdrawals) | Cut for focus; refunds drain the merchant balance |
| Redis | Stretch (read cache / rate limit) — outside the core |
| Distributed tracing | Monolith + correlation ids in logs suffice; stretch if services get extracted |
| Merchant KYC/onboarding, real compliance (PCI/Bacen) | The system is a simulated PSP; PCI posture = never store sensitive data |
| Web dashboard | REST API only; Swagger UI for exploration |

---

## 2. Decision Register

Decisions closed in debate, listed for traceability. Full rationales in the ADRs (§15).

| # | Decision | Value |
|---|---|---|
| D1 | Architecture | Modular monolith, hexagonal, extractable into microservices |
| D2 | Module split | By **bounded context** (payments, ledger, notifications), never by technical layer |
| D3 | Messaging | SNS/SQS FIFO on LocalStack via **direct AWS SDK v2** in our own channel adapters |
| D4 | Broker portability | Own event envelope + ports; swapping Kafka/Rabbit touches adapters only |
| D5 | Payment method | PIX, **dynamic QR only**; card is stretch (Strategy) |
| D6 | Webhook after expiration | **Resurrection**: trust the PSP, accept late payment + audit trail |
| D7 | Fee | Computed in `payments` (bps); **event carries the breakdown**; ledger is a dumb accountant |
| D8 | Fee on refund | **Returned proportionally** to the merchant (revenue reversal) |
| D9 | Balance | **Materialized projection** updated in the consumer's transaction (CQRS-lite) |
| D10 | Payouts | **Not** in v1 |
| D11 | Canonical state | `PENDING` (not "WAITING_PAYMENT") |
| D12 | Authentication | **Stripe-style API keys** (`psp_test_…`, SHA-256 at rest, indexable prefix) |
| D13 | Payment listing | Cursor pagination, in core scope |
| D14 | Data access | **JPA/Hibernate in payments** (separate domain entity) · **plain `JdbcClient` in ledger** (zero JPA) |
| D15 | Stack | Java **25** LTS · Spring Boot **4.1.x** · Maven multi-module · **PostgreSQL 16** · Flyway |
| D16 | Migrations | **Forward-only + expand/contract** (blue-green shares the Postgres; no rollback scripts) |
| D17 | Concurrent refunds | Pessimistic `SELECT FOR UPDATE` on the payment row, minimal scope |
| D18 | In-flight idempotency | `425 Too Early` + `Retry-After` (never block the request) |
| D19 | PSP timeout on creation | Retryable with backoff; payment born `PENDING` persisted; `FAILED` only after exhaustion |
| D20 | Governance | Acceptance matrix per milestone + full pipeline with security gates |
| D21 | Detail reads | `GET /payments/{txid}` straight from the table (no cache) |
| D22 | **Project name** | **Dargent** (*d'argent*, French "of silver/money"; Latin *argentum*). Criteria: tellable story, technical fit (`io.dargent`, `dargent_*`), zero direct collision, pronounceable. Accepted caveat: search adjacency with the "Argent" namespace (search engines rewrite the query — discovery via exact GitHub search). Defeated finalists: Cuprum (🥈), Trilho, Vintém, Peagem, Lastro, Ábaco, Prata; "Cobre" vetoed for colliding with a homonymous LatAm fintech in the same domain |

---

## 3. Architecture

### 3.1 Runtime overview

```
                        ┌────────────────────────── on-premises host ──────────────────────────┐
                        │                                                                       │
 merchant ──HTTP──▶ NGINX :8080 ──▶ api-blue :8081  ┐  same JVM, modules:                   │
 payer app ──▶  (upstream weights,  api-green :8082 ┘   [ payments | ledger | notifications ] │
                canary, DNS re-resolution)                   │        ▲                       │
                                                             │ outbox │ events              │
                                                             ▼        │ (SNS→SQS FIFO)        │
                                                   ┌──────────────────────────┐               │
                                                   │ LocalStack :4566         │               │
                                                   │  SNS payment-events.fifo │               │
                                                   │  SQS ledger/notification │               │
                                                   └──────────────────────────┘               │
                                                             ▲                                │
                                     HTTP (cob / webhook)    │                                │
                                                             │                                │
                                                   psp-simulator :8090 ───────────────────────┘
                                                   (merchant PSP + payer bank + chaos)
                                                             │
                                                   PostgreSQL :5432  ◀── source of truth
                                                   (schemas: payments | ledger | notifications)
```

### 3.2 Monolith modules (Maven multi-module)

| Module | Responsibility | Style |
|---|---|---|
| `modules/payments` | Full lifecycle: idempotency, outbox, webhook intake, reconciliation, expiration, refunds, BR Code | Full hexagonal, rich domain (JPA at the edge) |
| `modules/ledger` | Append-only double entry, balance projection, balance proof, D+1 settlement | Pure event consumer, `JdbcClient` |
| `modules/notifications` | Consumes events, records "notifications", phase 2 | Pragmatic, plain JPA |
| `modules/shared` | Minimal cross-cutting: `Money`, event envelope, error contract, JSON serialization | No business rules (junk drawer forbidden) |
| `apps/api` | Aggregates the modules, embedded Boot, schedulers, messaging adapters, Security, controllers | — |
| `apps/psp-simulator` | **Separate application**: merchant-side PSP + payer bank + chaos knobs | The honest "outside world" |

### 3.3 Cross-module laws (enforced by ArchUnit + import/FQN script in CI)

1. A module **never** imports another module's adapter — only ports (`domain.port` / `application.port`).
2. `payments` **never calls** `ledger` synchronously. `ledger` is pure downstream (consumer).
3. **Zero FK and zero JOIN across schemas** in Postgres. Each module owns its schema.
4. Cross-module communication is **events only** (outbox → SNS → SQS).
5. PSP webhook payloads **never** become domain entities directly — translated at the boundary (anti-corruption layer).
6. `shared` depends on no module.
7. `SecurityConfig` is the single source of authorization; **a new endpoint without an explicit rule is a violation** (AGENTS.md rule).

### 3.4 Why this permits future extraction

The topology is already microservices — it just runs in one JVM: separate data ownership, communication only through per-consumer queues, broker adapters isolated. Extracting `ledger` = moving the consumer to another JVM pointed at the same topics. Route: extract `ledger` first (trivial), then `payments`.

### 3.5 Module-internal pattern

```
<module>/src/main/java/io/dargent/<module>/
├── domain/            → rich entities, self-validating VOs, typed exceptions (zero framework)
│   ├── model/
│   └── port/          → in (use cases) | out (repos, publisher, PSP client, Clock)
├── application/       → use-case services (depend on ports only)
└── adapter/
    ├── in/            → REST (thin), event consumers
    └── out/           → persistence (JPA/JdbcClient), PSP client, SNS publisher
```

Domain is **pure where the money lives** (`payments`, `ledger`); pragmatism at the periphery (`notifications`). Entities with behavior, **zero setters**, forward-only lifecycle guarded inside the entity (invalid transition throws → 409), factory methods, identity equality, self-validating VOs (`Money`, `Txid`, `EndToEndId`), typed domain exceptions.

---

## 4. Payment Domain (PIX)

### 4.1 State machine

```
                 confirm (webhook / reconciler / resurrection)
   ┌─────────┐ ─────────────────────────────────▶ ┌───────────┐
   │ PENDING │                                    │ CONFIRMED │──▶ partial refund ──┐
   └────┬────┘                                    └───────────┘◀──                  │
        │                                              │        another partial     ▼
        │ expire (scheduler)                           │        refund       ┌─────────────────────┐
        ▼                                              │                     │ PARTIALLY_REFUNDED  │
   ┌─────────┐   resurrection (late confirm) ──────────┘                     └──────────┬──────────┘
   │ EXPIRED │ ────────────────────────────────────────────────────────────▶           │ refund zeroes it ▼
   └─────────┘        (becomes CONFIRMED late=true + audit)                      ┌──────────┐
        │                                                                        │ REFUNDED │ (terminal)
        │ PSP timeout exhausted on creation (D19)                                └──────────┘
        ▼
   ┌─────────┐
   │ FAILED  │ (terminal)
   └─────────┘
```

**Rules:**
- **Every transition is a conditional UPDATE**: `UPDATE payments SET status=:new, version=version+1 WHERE id=:id AND status IN (:allowed) AND version=:v`. `rows affected = 0` → lost the race → re-read and decide. The database arbitrates.
- `EXPIRED` is **not terminal** (D6): late confirmation resurrects with `late=true` + audit entry.
- The transition guard lives **inside the `Payment` entity** (violation throws `InvalidTransitionException` → 409); the conditional UPDATE is the last line of defense.

| From → To | Trigger | Guard |
|---|---|---|
| `PENDING → CONFIRMED` | Valid `payment.confirmed` webhook (HMAC ok, dedupe ok) **or** reconciler **or** resurrection | accepts from `PENDING` **or** `EXPIRED` |
| `PENDING → EXPIRED` | Expiration scheduler | `expires_at < now()` |
| `PENDING → FAILED` | PSP unavailable after creation retries exhausted (D19) | attempts ≥ max |
| `CONFIRMED → PARTIALLY_REFUNDED` | Partial refund created | after refund: `Σ refunds < amount` |
| `CONFIRMED → REFUNDED` | Refund consuming the remainder | `Σ refunds = amount` |
| `PARTIALLY_REFUNDED → PARTIALLY_REFUNDED` | Another partial refund | same |
| `PARTIALLY_REFUNDED → REFUNDED` | Refund zeroing the remainder | same |

### 4.2 PIX specifics

| Concept | Decision |
|---|---|
| **txid** | Public charge id, **25 alphanumeric chars** (Bacen cap; ULID is 26 — doesn't fit!). App-generated random alnum + unique constraint + bounded retry on collision |
| **endToEndId** | Network-wide PIX id, PSP-generated; fills `payments.end_to_end_id` on confirmation; webhook dedupe key (`endToEndId + type`) |
| **Dynamic QR** | `cob` with txid and fixed amount only. The app **generates the BR Code** (EMV payload with CRC16-CCITT) — own implementation, tested |
| **Validity** | `expires_at` **copied from the PSP response** — the PSP owns the charge's validity (simulator default: 30 min) |
| **Money** | `Money` = `cents: Long` + currency. BRL only. **Never float** — not in DB, not in JSON, not in memory. Fee in **basis points** (default 100 bps = 1%, configurable) |

### 4.3 Business policies

| Policy | Decision |
|---|---|
| **Retryable creation (D19)** | `POST /payments` persists `PENDING` + idempotency, calls the PSP with retry/backoff; exhausted → `FAILED`. A down PSP never "loses" the merchant's request |
| **Resurrection (D6)** | Confirmation webhook for an expired charge: accept (trust the PSP — rejecting valid money is worse), `late` flag, audit. A webhook beyond the **5-min anti-replay window** is rejected — and the reconciler saves the day |
| **Reconciliation** | Job scans `PENDING` unconfirmed past a threshold → `GET /cob/{txid}` at the PSP → acts on its truth. Covers lost/delayed/rejected webhooks |
| **Expiration** | Scheduler with partial index (`WHERE status='PENDING' AND expires_at < now()`), conditional UPDATE. No delayed SQS messages (15-min max delay can't cover hour-long charges) |
| **Refunds (D17)** | N partials per payment. Rule `Σ refunds ≤ amount`. One transaction: `SELECT FOR UPDATE` on the payment row → validate remainder → insert refund → bump `version` → outbox |
| **Fee on refund (D8)** | Returned proportionally: a 40% refund returns 40% of the fee to the merchant (revenue reversal in the ledger) |

### 4.4 Main flow (sequence)

```
Merchant            api/payments              psp-simulator             LocalStack           ledger
   │  POST /payments      │                        │                       │                  │
   │  Idempotency-Key     │  INSERT idem+payment   │                       │                  │
   │─────────────────────▶│  POST /cob ───────────▶│ txid, BR Code, expiry │                  │
   │                      │◀─── 201 (PENDING) ─────│                       │                  │
   │◀── 201 + txid + QR ──│  outbox: payment.created                        │                  │
   │                      │      relay ───────────────────────────────────▶│ SNS→SQS ────────▶│ (pending credit record)
   │        payer pays the QR at the bank (simulator endpoint)            │                  │
   │                      │◀── signed webhook (HMAC + timestamp) ──────────│                  │
   │                      │ validate HMAC, dedupe, conditional UPDATE      │                  │
   │                      │ → CONFIRMED, fee in bps, endToEndId            │                  │
   │  GET /payments/{txid}│  outbox: payment.confirmed (amount, fee, net)  │                  │
   │─────────────────────▶│      relay ───────────────────────────────────▶│ SNS→SQS ────────▶│ DR clearing / CR pending+fees
   │◀── status + values ──│                                                │                  │
```

---

## 5. Data Model

Postgres schemas per module; Flyway with per-module locations (each jar carries its migrations); **forward-only** (D16). Migration versioning convention: payments V1xx, ledger V2xx, notifications V3xx (gap numbering prevents cross-module conflicts and signals ownership).

### 5.1 Schema `payments`

**`payments`**

| Column | Type | Notes |
|---|---|---|
| `id` | UUIDv7 | app-generated (time-ordered, index-friendly) |
| `txid` | varchar(25) | **unique**, alphanumeric |
| `merchant_id` | uuid | inherited from the API key, never from the payload |
| `amount_cents` | bigint | Money |
| `status` | enum | `PENDING, CONFIRMED, PARTIALLY_REFUNDED, REFUNDED, EXPIRED, FAILED` |
| `version` | int | optimistic locking |
| `expires_at` | timestamptz | copied from the PSP |
| `end_to_end_id` | varchar | null until confirmation |
| `fee_cents`, `net_cents` | bigint | filled on confirmation (D7) |
| `late_confirmation` | boolean | resurrection (D6) |
| `created_at`, `confirmed_at` | timestamptz | |

Indexes: unique(`txid`), partial `WHERE status='PENDING' AND expires_at < now()`, (`merchant_id`, `created_at DESC`) for listing.

> **Note:** V107 (`description` column restore) was SKIPPED — the column already exists in V102 from E1.

**`idempotency_keys`** — `key` unique · `request_fingerprint` (body hash) · `response_snapshot` (http status + JSONB) · `payment_id` · `state` (`IN_FLIGHT`/`COMPLETED`) · cleanup job > 24h.

**`webhook_events`** — `provider_event_id` (= `endToEndId + type`) **unique** (dedupe) · `payload_raw` JSONB **immutable** (parse replay) · `status` (`RECEIVED→PROCESSED/IGNORED`) · `signature_valid` · `received_at`.

**`refunds`** — `id` UUIDv7 · `payment_id` · `amount_cents` · `fee_refund_cents` · `net_refund_cents` · `reason` · created under the payment's pessimistic lock.

**`outbox`** — `id` UUIDv7 · `aggregate_id` · `type` · `version` · `payload` JSONB · `request_id` · **delivery lifecycle**: `status` (`PENDING/SENT/FAILED/EXHAUSTED`) · `attempt_count` · `next_attempt_at` (backoff 30s→2min→5min) · `published_at`. `SELECT … FOR UPDATE SKIP LOCKED` in the relay.

**`audit_log`** — minimal: `command_name` · `actor_key_id` · `merchant_id` · `aggregate_id` · `request_id` · `created_at`. The "who" of commands (the "what" already lives in aggregate events and raw webhooks).

### 5.2 Schema `ledger`

**`accounts`** — tiny chart of accounts:

| Account | Type | Represents |
|---|---|---|
| `ASSET:PSP_CLEARING` | Asset | money physically at the PSP |
| `LIABILITY:MERCHANT:{id}:PENDING` | Liability | received, not yet settled (D+1 simulated) |
| `LIABILITY:MERCHANT:{id}:AVAILABLE` | Liability | settled, available for refunds |
| `REVENUE:PLATFORM_FEES` | Revenue | the platform fee — the business model |

**`journal_entries`** (header) — `id` · `event_id` **unique** (consumer idempotency) · `description` · `occurred_at`.

**`ledger_entries`** (lines, **append-only**: no UPDATE/DELETE — not in code, not in DB grants) — `id` · `journal_id` · `account` · `direction` (`DR`/`CR`) · `amount_cents` · application constraint + audit job ensure `Σ DR = Σ CR` per journal.

**`balances`** (CQRS-lite projection, D9) — `account` unique · `pending_cents` · `available_cents` — updated **in the same transaction** as the line inserts. `available ≥ refund` validation uses the projection; truth is the lines (proof job compares projection vs `SUM`).

### 5.3 Accounting movements (example: R$ 100.00, 100 bps fee = R$ 1.00)

```
[1] Payment confirmed (webhook validated)
    DR ASSET:PSP_CLEARING             100.00
    CR LIABILITY:MERCHANT:m1:PENDING   99.00
    CR REVENUE:PLATFORM_FEES            1.00

[2] D+1 settlement (job, simulated clock)
    DR LIABILITY:MERCHANT:m1:PENDING   99.00
    CR LIABILITY:MERCHANT:m1:AVAILABLE 99.00

[3] Partial refund of R$ 40.00 (money returns to the payer)
    DR LIABILITY:MERCHANT:m1:AVAILABLE 40.00
    CR ASSET:PSP_CLEARING              40.00

[4] Proportional fee reversal (40% of 1.00 = 0.40) — D8
    DR REVENUE:PLATFORM_FEES            0.40
    CR LIABILITY:MERCHANT:m1:AVAILABLE  0.40

Merchant final balance: 59.40 available | net fees: 0.60 | clearing: 60.00 ✓
```

---

## 6. API Contracts

Base: `/v1` (path versioning, pragmatic). Auth: `Authorization: Bearer psp_test_…` (D12). Money in JSON: **integer cents**. Idempotency on mutations: `Idempotency-Key` header.

### 6.1 Resources

| Method & path | Auth | Description |
|---|---|---|
| `POST /v1/payments` | API key | Creates PIX charge. `201` + `Location` |
| `GET /v1/payments/{txid}` | API key | Detail + status + BR Code. Another merchant's → **404** (not 403) |
| `GET /v1/payments?cursor=&limit=` | API key | History, cursor pagination (default 20, max 100) |
| `POST /v1/payments/{txid}/refunds` | API key | Partial (amount in body) or total (empty body) refund |
| `GET /v1/payments/{txid}/events` | API key | Aggregate event trail |
| `POST /webhooks/psp` | HMAC (no API key) | PSP intake, fail-closed |

**Tenancy:** `merchant_id` comes **only** from the API key — never path, query or body. IDOR dead by design: "the tenant is never client input".

### 6.2 Examples

```http
POST /v1/payments
Authorization: Bearer psp_test_9f2c…
Idempotency-Key: 4e7a2c10-…
Content-Type: application/json

{ "amount": 10000, "description": "Order #123", "expiresIn": "PT30M" }
```

```http
HTTP/1.1 201 Created
Location: /v1/payments/8KD4Z9X2Q7W1M5T3R6Y0A1B2C
X-Request-Id: 7c1e…

{
  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
  "status": "PENDING",
  "amount": 10000,
  "currency": "BRL",
  "expiresAt": "2026-08-28T15:30:00Z",
  "brcode": "00020126580014br.gov.bcb.pix…6304AB12",   ← EMV + CRC16, generated by us
  "expiresIn": "PT30M"
}
```

Signed PSP webhook:

```http
POST /webhooks/psp
X-PSP-Timestamp: 1787932800
X-PSP-Signature: hex(HMAC-SHA256(secret, "1787932800" + "." + rawBody))
Content-Type: application/json

{ "eventId": "psp-evt-991", "type": "payment.confirmed",
  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C", "endToEndId": "E904038…",
  "amount": 10000, "paidAt": "2026-08-28T15:02:11Z" }
```

### 6.3 Error catalog (RFC 9457 `application/problem+json` + `code`)

Clients branch on `code`, never on messages. **A single `ErrorResponseWriter` emits all** (global handler, auth/HMAC filters, entry points) — no per-filter formats.

| `code` | HTTP | When |
|---|---|---|
| `invalid_request` | 400 | Validation (body includes field map) |
| `unauthorized` | 401 | Missing/invalid API key |
| `invalid_signature` | 401 | Webhook HMAC mismatch |
| `signature_expired` | 401 | Webhook timestamp outside the 5-min window (anti-replay) |
| `payment_not_found` | 404 | Nonexistent txid or another merchant's |
| `idempotency_key_conflict` | 409 | Same key + **different body** (non-negotiable) |
| `payment_not_refundable` | 409 | Status doesn't allow refund |
| `refund_exceeds_remaining` | 409 | `Σ refunds + amount > original` |
| `invalid_transition` | 409 | Illegal state transition |
| `idempotency_key_in_flight` | **425** + `Retry-After` | Retry arrived while the 1st request processes (D18) |
| `psp_unavailable` | 502 | PSP unreachable/unhealthy after creation retries; payment persisted as FAILED |
| `internal` | 500 | Logs method+URI+exception; **never leaks internal message** |

Protocol detail: `NoResourceFoundException` → canonical 404 (never 500).

### 6.4 Cursor pagination

Opaque `cursor` = base64(`txid` + `created_at` of the last row); response carries `nextCursor`; fixed ordering `created_at DESC, txid DESC` (stable under insertion).

---

## 7. Events & Messaging

### 7.1 Event envelope (the contract that is ours — no broker leaks into it)

```json
{
  "eventId": "0198f6a2-…",
  "type": "payment.confirmed",
  "version": 1,
  "aggregateId": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
  "merchantId": "…",
  "requestId": "7c1e…",
  "occurredAt": "2026-08-28T15:02:12Z",
  "payload": { "amount": 10000, "fee": 100, "net": 9900, "late": false }
}
```

### 7.2 Catalog

| Type | Producer | Consumers | Note |
|---|---|---|---|
| `payment.created` | payments | ledger (nothing to journal yet — record), notifications | cob created, PENDING |
| `payment.confirmed` | payments | **ledger** (entry [1]), notifications | carries amount/fee/net breakdown + `late` flag |
| `payment.expired` | payments | notifications | — |
| `payment.failed` | payments | notifications | PSP exhausted (D19) |
| `refund.created` | payments | **ledger** (entries [3]+[4]), notifications | carries amount/feeRefund/netRefund |

### 7.3 Topology (LocalStack)

```
SNS topic:  payment-events.fifo            (MessageGroupId = txid → per-payment ordering)
   ├── SQS ledger-events.fifo              (subscription filter: ledger types)
   │      └── SQS ledger-events-dlq.fifo   (redrive: maxReceiveCount=5)
   └── SQS notification-events.fifo
          └── SQS notification-events-dlq.fifo
```

- **Ordering**: `MessageGroupId = txid` — exact analog of partitioning Kafka by key
- **At-least-once**: duplication is normal; **consumer idempotent on `eventId`** is law (unique `event_id` in the journal)
- **DLQ** per queue with redrive policy; visibility timeout calibrated above worst processing time
- **Provisioning**: topics/queues/filters created at app startup via AWS SDK (self-contained compose, zero manual scripts)
- LocalStack **Community** covers SNS/SQS; Testcontainers has its own module for tests

### 7.4 Outbox (the transactional bridge)

1. Use case persists aggregate **and** outbox row in the **same transaction**
2. Relay (`@Scheduled`, N workers): `SELECT … FOR UPDATE SKIP LOCKED` of `PENDING` with `next_attempt_at <= now()` → publishes to SNS via the `EventPublisher` port → marks `SENT`
3. Publish failure → `FAILED`, `attempt_count++`, backoff `30s → 2min → 5min`; after N attempts → **`EXHAUSTED`** (no longer polled)
4. **Administrative requeue**: audited endpoint resets `EXHAUSTED`/`FAILED` to `PENDING`
5. Published-then-died → duplicate in queue → **fine by design** (idempotent consumer)
6. **Republish tool**: re-publish outbox events by period/aggregate = our "replay" (replaces Kafka offset rewind)

### 7.5 Broker portability (D3/D4)

| Concern | SQS/SNS (v1) | Kafka | RabbitMQ |
|---|---|---|---|
| Ordering | `MessageGroupId` | record key | consistent-hash exchange |
| DLQ | native redrive policy | DLQ topic | dead-letter exchange |
| Retry | visibility timeout | local retry | nack/requeue |
| Replay | outbox republish | offsets | not native |

Ports: `EventPublisher` (relay calls) + an event handler per consumer. Envelope, dedupe and semantics are ours; each broker adapter encapsulates the specific bits (**Channel Adapter**, Hohpe & Woolf). Swapping brokers touches the messaging module only. **Spring Application Events is not the bus** (in-JVM communication would hide the distributed cost).

---

## 8. Security

### 8.1 API keys (D12 — Stripe style)

- Format `psp_test_<43 base62 chars>`; **indexable prefix** for lookup, **SHA-256 hash in the DB** (never the raw key); **constant-time** comparison
- Bound to a merchant; `merchant_id` comes **only** from the credential (§6.1); 404 (not 403) for another merchant's resources
- Spring Security 7 with **one custom filter** — no OAuth server (ceremony without use)

### 8.2 PSP webhook (fail-closed)

- `HMAC-SHA256(timestamp + "." + rawBody)` in `X-PSP-Timestamp`/`X-PSP-Signature` headers
- **Anti-replay**: timestamp older than 5 min → `signature_expired` (401). Desired side effect: a webhook delayed past the window is **legitimately rejected — and the reconciler confirms** (security and resilience proving themselves in the same test)
- Route is `permitAll` at the HTTP layer **only with** validation in the filter; raw payload saved **always**, even on invalid signatures (attack audit)
- Behind NGINX: the app must see the same URL the PSP dialed + forwarded headers configured in the prod profile

> **Endpoint-driven intake (E4 spec §3.1):** The webhook endpoint captures the raw body once, passes it through the validator, then the use case. The intake is **not** a servlet filter; it's a controller route that owns the raw-capture-once contract. Fail-closed is proven by tests, not by filter chain position. This avoids the filter-chain re-serialization trap (body consumed before filter sees it) and keeps the use case Spring-free.

### 8.3 PCI posture & production

- Never store sensitive payment data — only PSP tokens (PIX doesn't even expose them; stretch-card would follow the rule: token + last 4)
- **`ConfigValidator` aggregated fail-fast** *(lands M1/E3; nothing of the sort exists yet — M0/M1 boots with dev defaults)*: boot aborts listing ALL problems (unresolved placeholders, short secrets, static AWS keys in prod)
- **Production lockdown proven by IT** *(lands M4/E11)*: Swagger/api-docs absent, actuator health-only with `show-details: never`, isolated management port, API key mandatory on business endpoints

---

## 9. Observability

| Pillar | Decision |
|---|---|
| **Logs** | Built-in Boot 4 structured JSON (ECS profile, zero dependencies). Fields on every line: `request_id`, `payment_id`/`aggregate_id`, `merchant_id` |
| **Correlation** | `X-Request-Id` filter: accepts (validated charset/length), generates when absent, **echoes in the response**, propagates to MDC and outbox |
| **Metrics** | Micrometer + `/actuator/prometheus`: (1) payments by status transition, (2) **outbox lag** (age of oldest unpublished event — *the* architecture metric), (3) DLQ depth, (4) reconciler confirmations (measures lost webhooks!), (5) webhook signature failures, (6) outbox attempts by status |
| **Health** | Liveness/readiness separated; readiness gated on Postgres + SNS/SQS (LocalStack); actuator with minimal exposure |
| **Tracing** | Out (monolith + correlation ids); stretch if services get extracted |

Full details in [`observability.md`](observability.md); targets in [`slos.md`](slos.md).

---

## 10. Testing Strategy

### 10.1 Pyramid (tilted toward integration — a conscious choice)

| Layer | Tooling | Covers |
|---|---|---|
| Pure unit (no Spring) | JUnit 6 + AssertJ | State machine (allowed/forbidden table), `Money`, fee + proportional reversal, BR Code EMV + CRC16, txid, VOs |
| Integration | **Testcontainers 2.0** (Postgres + LocalStack singleton, `@ServiceConnection`) + **WireMock** (stubbed PSP, `wiremock-spring-boot`) | Every real seam |
| E2E | 2–3 suites with real monolith + psp-simulator | Full assembly end to end |

**Golden mock rule:** only the outside world (PSP via WireMock). **Never** mock our own database, queue, outbox. `RestTestClient` (Framework 7) for HTTP tests; injected `Clock` bean (time travel, zero sleeps); **Awaitility** for everything eventual; deterministic races with `ExecutorService` + `CyclicBarrier`; `@Tag("chaos")`/`@Tag("stress")` suites in a separate job; singleton containers (one Postgres/LocalStack for the whole suite).

### 10.2 Scenario catalog (each test guards a money/race guarantee)

**Idempotency:** 1. same key+body → same answer, **one** payment · 2. same key+different body → `409` · 3. in-flight key → `425` + `Retry-After` · 4. retry after success → snapshot, zero new side effects · 5. cleanup job > 24h

**Webhooks:** 6. invalid HMAC → rejected + raw payload still saved · 7. expired timestamp → `signature_expired` · 8. duplicate (`endToEndId`+type) → processed once · 9. out-of-order/late → consistent final states, unknown types `IGNORED` · 10. **`payload_raw` replay** → same result · 11. **expiration+confirmation race → resurrection exactly once + audit**

**Races:** 12. two concurrent 60% refunds → one passes, one fails elegantly, invariant preserved · 13. N threads on the same conditional UPDATE → exactly one wins · 14. parallel relay with `SKIP LOCKED` → no double publication · 15. concurrent identical `POST /payments` (same key) → one payment

**Outbox/events:** 16. relay "dead" → events wait; relay returns → publishes · 17. published-then-died → queue duplicate → **consumer dedupes by `eventId`** · 18. poison message → DLQ, auditable count · 19. backoff → `FAILED` → `EXHAUSTED` → admin requeue → `SENT` · 20. republish replays a period without double-journaling

**Ledger:** 21. every journal closes (`Σ DR = Σ CR`) — invariant asserted after each money scenario + daily-proof job test · 22. **property test (jqwik)**: random payment/partial-refund sequences → `projection == SUM(lines)` always · 23. refund beyond available → rejected, projection intact · 24. D+1 settlement moves pending→available exactly once

**PSP chaos (WireMock):** 25. creation timeout → `PENDING`, backoff retries → `FAILED` only after exhaustion · 26. **webhook never arrives → reconciler polls the PSP and confirms alone** (the soul of the project) · 27. late webhook beyond anti-replay → rejected → reconciler confirms

**Production shape:** 28. lockdown IT: prod profile boots with Swagger/api-docs absent, actuator health-only

### 10.3 Order & discipline

TDD in the pure domain first (state machine, Money, BR Code) → integration right after each seam stands → chaos/races last. Test names as specifications (`concurrent_refunds_beyond_balance_are_rejected_and_balance_stays_consistent`). **Per-module** coverage floors, measured **after** ITs (combined with unit). Failsafe: `./mvnw verify` = unit + `*IT`; `-Dskip.unit.tests=true` for ITs only. Full detail in [`testing-playbook.md`](testing-playbook.md).

---

## 11. CI/CD & Release Engineering (GitHub Actions)

### 11.1 Pipeline (commit → always the same jar + image)

```
build (PR gate)
├── ./mvnw test                        → pure unit
├── ArchUnit + scripts/check-boundaries.sh  → double boundaries (semantic + import/FQN)
├── ./mvnw spotbugs:check              → 0 bugs
├── OWASP Dependency-Check             → cached NVD + NVD_API_KEY; report-only, degrades to cache
├── ./mvnw test -Dtest='*IT'           → Testcontainers (Postgres + LocalStack + WireMock)
├── ./mvnw jacoco:check                → combined unit+IT coverage, per-module floors
└── clean package + jar artifact       → consumed by the jobs below

image (needs: build)
├── docker build (tag = git SHA) + immutable digest record
├── non-root gate (docker run … id → fails on uid=0)
├── Trivy 2-pass: SARIF → Security tab (advisory) + table fixable HIGH/CRITICAL → gate
└── SBOM CycloneDX + digest → artifacts

security (parallel)
├── CodeQL (Java SAST)
└── Dependency Review on PRs (new dependency vuln/license → blocks)

runtime-smoke (needs: build — GATES the pipeline)
├── compose up (postgres + localstack + psp-simulator) → readiness polling
├── production jar + Flyway migrate
├── E2E happy path: create → pay at simulator → webhook → CONFIRMED → ledger journaled
├── chaos: webhook suppressed → reconciler confirms          ← our CI signature
└── graceful shutdown under load (drain) — regression breaks CI

performance (continue-on-error, consultative)
└── k6 budgets-as-code → POST /payments p95<250ms · GET /payments/{txid} p95<100ms · webhook p95<150ms
    (promoting to a hard gate = deliberate decision after calibrating 2–3 runs)

release (annotated tag vX.Y.Z)
└── all gates on the tagged commit → semver image + GitHub Release with jar + SBOM of the exact image
```

First-party actions pinned by version; third-party **pinned by commit SHA**. Dependabot active since M0. Gate policy documented in `docs/ci-vulnerability-gates.md`. **M0 note:** the build/image gates run from day one; SpotBugs/OWASP/JaCoCo/Trivy/SBOM/CodeQL/runtime-smoke/k6 activate at M4.

### 11.2 Blue-green deploy on-premises (no k8s)

| Element | Decision |
|---|---|
| Topology | `api-blue` :8081 / `api-green` :8082 + NGINX :8080 (`nginx:1.29-alpine`) |
| Flow | new version into the idle slot → readiness gate → **canary 10% with 30 s observation** → cutover → drain old slot; automatic abort to blue on any red signal |
| Rollback | instant (`rollback.sh` flips the upstream back) |
| Weights | runtime copy of the conf + `nginx -s reload` — the versioned template is **never** mutated |
| Already-learned gotchas (for free) | `down` instead of `weight=0` (doesn't exist → crash-looped someone's LB); `resolver 127.0.0.11 valid=10s` + `zone` + `resolve` on upstreams so recreated fleets are picked up without reload; `proxy_next_upstream error timeout`; passive checks `max_fails=3 fail_timeout=10s`; `keepalive 32` |
| Image | multi-stage, layered jar, base **digest-pinned**, non-root, read-only root FS + tmpfs, CPU/RAM limits, healthcheck with start period |
| Shutdown | `server.shutdown=graceful` + per-phase timeout |
| Deploy | **always by immutable tag**; rollback = redeploy the previous tag |
| Migrations | **expand/contract** (blue and green share the Postgres): add a column in one release, remove in the next — never rename/destructively migrate in the release that starts using the change |
| Schedulers | **no ShedLock**: brief two-instance overlap is harmless by design (conditional UPDATE, `SKIP LOCKED`, unique constraints) |
| Backup | scheduled `pg_dump` + **tested restore drill with evidence** in the runbook (Postgres is the truth; LocalStack is disposable) |

---

## 12. On-Premises Runtime (Compose)

| Service | Image | Port | Note |
|---|---|---|---|
| `nginx` | nginx:1.29-alpine | 8080 | blue-green LB |
| `api-blue` | ours (SHA tag) | 8081 | prod profile |
| `api-green` | ours (SHA tag) | 8082 | idle slot by default |
| `psp-simulator` | ours | 8090 | PSP + payer bank + chaos |
| `postgres` | postgres:16-alpine | 5432 | named volume; source of truth |
| `localstack` | localstack/localstack | 4566 | SNS+SQS; **no persistence** (disposable by design) |

Environment contract via `.env.example` (twelve-factor): `DARGENT_DB_*`, `AWS_ENDPOINT_URL`, `PSP_BASE_URL`, `PSP_WEBHOOK_SECRET`, `DARGENT_FEE_BPS`, `CHAOS_*` (simulator), shutdown knobs. **Sources 100% English** (identifiers, comments, logs).

**psp-simulator chaos knobs** (env): `CHAOS_WEBHOOK_DUPLICATE` (duplicates), `CHAOS_WEBHOOK_DELAY_MS` (delays; cap 30 000), `CHAOS_WEBHOOK_DROP_RATE` ("forgets" a fraction), `CHAOS_PSP_ERROR_RATE` (request-side 503), `CHAOS_PSP_LATENCY_MS` (request-side delay; cap 30 000), `CHAOS_SEED` (seeds the probabilistic knobs; unset = system-random). Endpoints: `POST /cobs`, `GET /cobs/{txid}`, `POST /cobs/{txid}/payments` (the "payer bank" pays the QR → fires the signed webhook), `GET /health`.

---

## 13. Delivery Roadmap

Each milestone closes with: green tests in the full pipeline, **filled acceptance matrix** (requirement → implementation → test → evidence), release notes + CHANGELOG, recorded lessons. Residual deviations are **declared** with an owner.

| M | Name | Scope | Key acceptance criteria (full matrix in `tasks/`) |
|---|---|---|---|
| **M0** | Skeleton | Maven multi-module + empty modules with ArchUnit passing, compose (postgres/localstack/nginx/psp-simulator stub), CI running build gates, Flyway per schema, AGENTS.md + docs base | CI green on a real PR; ArchUnit rejects an illegal import (proving test) |
| **M1** | Happy path | Create cob (PSP + BR Code) → PENDING → webhook → CONFIRMED; full idempotency; API keys; canonical errors | Catalog 1–5, 13, 15, 25 green; two identical requests → one payment; duplicate webhook → one confirmation |
| **M2** | Events | Outbox + relay + SNS/SQS FIFO; ledger consuming (entry [1]); balance projection; basic notifications | Catalog 14, 16–18, 21–22 green; queue duplicate → one journal; `ΣDR=ΣCR` after every scenario |
| **M3** | Suffering | Refunds (partial/total/concurrent), expiration, resurrection, reconciler, D+1 settlement, DLQ + backoff + EXHAUSTED + requeue | Catalog 6–12, 19–20, 23–24, 26–27 green; signature scenario (11) and reconciler (26) in CI |
| **M4** | Finish | Metrics + JSON logs + correlation; blue-green with canary + rollback; runtime-smoke in CI; tag releases + SBOM; README with diagram + final ADRs; full acceptance matrix; restore drill; full quality gates (SpotBugs/OWASP/JaCoCo/Trivy/CodeQL) | v1→v2 deploy with proven zero downtime; instant rollback exercised; GitHub Release with SBOM |
| **M5** | Stretch | Simulated card (2nd Strategy), k6 as hard gate, Redis read cache, webhook reprocessing via admin | Card added **without touching** the PIX domain (abstraction proof) |

---

## 14. Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Scope creep (card/Redis/k8s early) | Eternally unfinished project | Non-goals §1.2; card/Redis locked at M5 |
| LocalStack quirks (SNS→SQS FIFO, signatures) | Integration surprises | Testcontainers from M0; idempotent provisioning at startup; own envelope shrinks the contact area |
| Slow CI (Testcontainers + many jobs) | Slow PRs, avoided suite | Singleton containers; fast gates first; chaos/stress tagged separately; NVD/Trivy caches |
| Migrations breaking blue-green | Downtime deploys | Expand/contract mandatory (D16); migration smoke in runtime-smoke |
| Event duplication in local production | Money journaled 2x | At-least-once assumed; consumer idempotent **by design** and tested (scenario 17) |
| Host clock vs HMAC window | Legitimate webhooks rejected | 5-min window + reconciler as the net; host clock monitored in health |
| HMAC/API-key secret leak in repo | Total compromise | Secrets via env only; ConfigValidator blocks default secrets in prod; `.dockerignore`/`.gitignore` for secrets |
| Documentation rotting | Portfolio loses value | Docs as milestone DoD criteria; acceptance matrix mandatory to close an M |

---

## 15. ADR Register (index)

Full files in `docs/adr/` from M0 on. Decision → rejected alternative → consequence.

| ADR | Title (one-line decision) |
|---|---|
| 0001 | Modular monolith with extraction seams, instead of microservices from day one |
| 0002 | Hexagonal with rich domain; Maven split by bounded context (never by layer) |
| 0003 | SNS/SQS FIFO (LocalStack) via direct SDK v2, instead of Kafka |
| 0004 | Own event envelope + Channel Adapters for broker portability |
| 0005 | Transactional outbox with hand-rolled `SKIP LOCKED` relay (vs Debezium, vs Spring Modulith) |
| 0006 | PIX dynamic QR as the only core method; card as a future Strategy |
| 0007 | State transitions via conditional UPDATE + optimistic locking (the database arbitrates) |
| 0008 | Resurrection of expired payments trusting the PSP, with audit |
| 0009 | Fee computed in payments (bps); event carries the breakdown; dumb-accountant ledger |
| 0010 | Proportional fee reversal on refund (revenue reversal) |
| 0011 | Balance as a transactional CQRS-lite projection, with a proof job against the lines |
| 0012 | No payouts in v1; refunds drain `AVAILABLE` |
| 0013 | Stripe-style API keys instead of JWT/OAuth2 |
| 0014 | Forward-only migrations with expand/contract (zero rollback scripts) |
| 0015 | Postgres as source of truth; disposable LocalStack with outbox republish as replay |
| 0016 | NGINX blue-green with canary on-premises, no k8s and no ShedLock |
| 0017 | Release by immutable tag with SBOM; deploys never by moving tag |

---

## Appendix A — Glossary

| Term | Meaning |
|---|---|
| **PSP** | Payment Service Provider — payment infrastructure provider (Stripe, Efí…) |
| **txid** | Charge identifier defined by the receiver (max 25 alphanumeric) |
| **endToEndId** | End-to-end PIX identifier on the network (PSP-generated, immutable) |
| **BR Code** | PIX QR code (EMV-QRCPS-MPM payload with CRC16-CCITT) |
| **Outbox** | Pattern: event persisted in the same transaction as the aggregate, published later by a relay |
| **Double entry** | Accounting where every journal has debits = credits (journal + entries) |
| **Projection** | Read model derived from events, here kept in the consumer's transaction |
| **Channel Adapter** | Pattern (Hohpe & Woolf): adapter encapsulating a middleware's specifics behind an own port |
| **DLQ** | Dead-letter queue — destination of messages that failed past the limit |
| **Expand/contract** | Migration discipline compatible with versions N and N+1 running simultaneously |
| **Blue-green** | Two identical fleets; the LB decides who takes traffic; deploy = flip |
| **Canary** | Small traffic fraction to the new version before full cutover |

## Appendix B — Inspiration credits

Patterns and habits consciously stolen (full notebook: internal reference analysis):

- **[ecommerce](https://github.com/daniel-castilho/ecommerce)** — docs culture (lessons/release notes/playbook/AGENTS.md), SENT/FAILED/EXHAUSTED + requeue lifecycle, ArchUnit everywhere
- **[spotpobre](https://github.com/daniel-castilho/spotpobre)** — 4-job CI pipeline (Trivy 2-pass, SBOM, non-root, shutdown-under-load), blue-green with canary + NGINX/Docker gotchas, evidence-based acceptance matrix, production lockdown IT, consultative k6
- **[flowtxt](https://github.com/daniel-castilho/flowtxt)** — canonical single ErrorResponseWriter, as-built rich domain, per-module JaCoCo, 3-tier releases, aggregated ConfigValidator, CodeQL + Dependency Review

## Appendix C — Document history

| Version | Date | Changes |
|---|---|---|
| 1.0.2 | 2026-08-28 | English becomes canonical (repo is 100% EN); pt-BR original archived at `design-ptbr.md`; M0 note added to §11.1 |
| 1.0.1 | 2026-08-28 | Official naming: **Dargent** (D22) — rename sweep across all artifacts (package `io.dargent`, metrics `dargent_*`, `DARGENT_API_KEY`, `dargent-api` images) |
| 1.0.0 | 2026-08-28 | Initial version — full consolidation of the brainstorm (architecture, domain, data, API, events, security, testing, CI/CD, runtime, roadmap, risks, ADRs) |
