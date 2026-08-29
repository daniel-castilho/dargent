# Create Payment E3 — Technical Specification

## Epic E3 — First Command: `POST /v1/payments` (idempotency, API keys, error contract, BR Code)

**Priority:** P0 — the epic that turns the README's curl from documentation into reality
**Companions:** `create-payment-e3-backlog.md` · `create-payment-e3-implementation-sequence.md` · `ai-software-engineer-prompt-create-payment-e3.md`
**Baseline:** E0+E1+E2 closed (E2 closure commit `bb90f9d2`, CI run #9). The simulator is live; the payment domain is closed and proven.

---

## 1. Purpose

Deliver the first platform command end to end: an authenticated, idempotent `POST /v1/payments` that
persists `PENDING` + idempotency row + outbox row + audit row in **one transaction**, calls the PSP
(simulator) retryably **after commit**, composes the dynamic-QR BR Code, and answers `201`.
Plus the read side (`GET /v1/payments/{txid}`, cursor listing) and the platform error contract.
Everything E4 (webhook intake) and E6 (relay) consume — outbox rows, error writer, API-key filter —
is born here in final shape.

**Jackson 3 warning (lesson #13):** Boot 4.1 resolves **Jackson 3** (`tools.jackson.*`). There is no
`com.fasterxml.jackson.databind` on the classpath. Every serializer/import in this epic is `tools.jackson`.

## 2. Scope

### In scope
- `POST /v1/payments` — full lifecycle per §5.1 (idempotency semantics §5.1.3, PSP phase §5.7);
- API keys: table, hasher, `Authorization: Bearer` filter, dev seeding (§5.9);
- Canonical `ErrorResponseWriter` + problem+json catalog + `X-Request-Id` filter (§5.4);
- BR Code composer: EMV TLV + CRC16-CCITT, pure domain, golden vector (§5.5);
- Migrations V103–V107 (§3.2); outbox/idempotency/audit stores (§5.6);
- `GET /v1/payments/{txid}` + `GET /v1/payments?cursor=&limit=` (§5.2, §5.3);
- Event envelope (`payment.created`) written to the outbox — **rows only; the relay is E6**;
- Minimal `ConfigValidator` (aggregated fail-fast) for what this epic owns (§3.5) — pays the first half of DEBT-2.

### Out of scope
- Webhook intake (E4), relay/SNS/SQS (E6), expiration (E5), refunds (E8);
- Idempotency cleanup job (playbook 5, [M3] — E5/E9); live-key issuance policy (M4);
- Actuator hardening/management port (E11); Swagger annotations beyond springdoc defaults (E11 lockdown proves);
- Any change to `apps/psp-simulator`, `modules/ledger`, `modules/notifications`, CI workflow, compose topology.
  **Allowed exception:** env additions to the existing `api` service + `docker/.env.example` (§3.3) — this also
  pays E2's declared follow-up (`CHAOS_*` entries in `.env.example`).

## 3. Architectural constraints

### 3.1 Package shape (first residents of reserved places)

```
modules/payments
├── domain/model/          + BrCode (pure composer, §5.5)
├── application/           + CreatePaymentUseCase          ← first use case (E1 left this empty on purpose)
├── adapter/in/rest/       + PaymentController, ApiKeyAuthenticationFilter, RequestIdFilter, GlobalExceptionHandler
├── adapter/out/persistence/ + IdempotencyStore adapter, OutboxWriter adapter, AuditWriter adapter, PaymentQueryAdapter
└── adapter/out/psp/       + PspPort (domain port) + SimulatorChargeAdapter (RestClient)
modules/shared             + EventEnvelope record (+ Jackson 3 serializer) — shared's charter per AGENTS.md §2.1
apps/api                   + SecurityConfig (single source of truth, AGENTS.md §4.1), ErrorResponseWriter bean, wiring
```

Transactions live in the use case (standards §5). Domain stays pure (ArchUnit gate). The PSP call is a
**port** (`PspPort`) — the adapter is the only place that knows the simulator exists.

### 3.2 Migrations (forward-only, expand/contract; payments module Flyway location)

| File | Content |
|---|---|
| `V103__api_keys.sql` | `payments.api_keys`: `id uuid PK` · `merchant_id uuid NOT NULL` · `name varchar(80)` · `key_prefix varchar(16)` · `key_hash varchar(64)` (SHA-256 hex) · `created_at` · `revoked_at` nullable. Unique index on `key_hash`; partial index on `key_prefix WHERE revoked_at IS NULL` |
| `V104__idempotency_keys.sql` | `payments.idempotency_keys`: PK **(`merchant_id`, `idempotency_key`, `endpoint`)** — a deliberate refinement of design.md's "key unique" (per-tenant, per-endpoint); `request_fingerprint varchar(64)` · `state CHECK (IN ('IN_FLIGHT','COMPLETED'))` · `payment_txid varchar(25)` · `response_status int` · `response_body jsonb` · `created_at` · `completed_at` |
| `V105__outbox.sql` | `payments.outbox` exactly per design §5.1: `id uuid PK` · `aggregate_id` · `type` · `version` · `payload jsonb` · `request_id` · `status CHECK (IN ('PENDING','SENT','FAILED','EXHAUSTED')) DEFAULT 'PENDING'` · `attempt_count DEFAULT 0` · `next_attempt_at` · `created_at` · `published_at`; partial index `next_attempt_at WHERE status='PENDING'` (the relay's poll index, E6) |
| `V106__audit_log.sql` | `payments.audit_log`: `id uuid PK` · `command_name` · `actor_key_id uuid` · `merchant_id uuid` · `aggregate_id` · `request_id` · `created_at` (the "who" of commands — design §5.1) |
| `V107__payments_description.sql` | `ALTER TABLE payments.payments ADD COLUMN description varchar(140);` — header comment: restores the design §5.1 row dropped in the post-E1 drift (commit `c709066`); expand-only. **S0 verifies V102 first — if the column already exists, skip V107 and record the deviation** |

### 3.3 Config surface (env → compose; `.env.example` updated in the same epic)

| Property | Env | Default |
|---|---|---|
| `dargent.psp.base-url` | `PSP_BASE_URL` | `http://psp-simulator:8090` |
| `dargent.psp.callback-url` | `PSP_CALLBACK_URL` | `http://api-blue:8080/webhooks/psp` |
| `dargent.psp.create-max-attempts` | `PSP_CREATE_MAX_ATTEMPTS` | `3` |
| `dargent.psp.create-backoff-base-ms` | `PSP_CREATE_BACKOFF_BASE_MS` | `200` |
| `dargent.pix.profile.pix-key` | `DARGENT_PIX_KEY` | `dargent-dev-receber@example.com` |
| `dargent.pix.profile.receiver-name` | `DARGENT_RECEIVER_NAME` | `Dargent Dev LTDA` |
| `dargent.pix.profile.receiver-city` | `DARGENT_RECEIVER_CITY` | `SAO PAULO` |
| `dargent.api.dev-key` | `DARGENT_DEV_API_KEY` | *(unset — dev profile seeds only when present)* |

The BR Code is composed from **the API's own PIX profile** (§5.5 rationale) — not from the PSP response.

### 3.4 Dependency additions (locked)

`org.wiremock:wiremock-standalone`, **test scope**, `modules/payments` only — stubs the remote PSP in ITs
(the E2 stub-receiver trick cannot work here: the PSP is an *outbound* HTTP dependency). Nothing else.

### 3.5 Minimal ConfigValidator (DEBT-2, first installment)

Boot fails fast with an **aggregated report** listing ALL problems for: PIX profile (charset `[A-Za-z0-9 ]`,
lengths ≤ 25/≤ 15), `DARGENT_DEV_API_KEY` shape (§5.9) when set, unresolved PSP base/callback URLs
(`PSP_BASE_URL` placeholder unresolvable in a non-dev profile). No other checks — full validator stays M4.

## 4. Decision map (traceability)

| Spec element | Source |
|---|---|
| Request/response shape, `Location`, `expiresIn` as `Duration` | design §6.2 (verbatim) |
| Error catalog + single `ErrorResponseWriter` | design §6.3; coding-standards §4 |
| Cursor = opaque base64, `created_at DESC, txid DESC`, 20/100 | design §6.4, D13 |
| API keys: `psp_test_<43 base62>`, SHA-256 at rest, constant-time, one filter, Spring Security 7 | design §8.1, D12 |
| `PENDING` canonical state; tenant from credential; 404 not 403 | D11, AGENTS §3.7, design §6.1 |
| PSP call retryable; `PENDING` persisted first; `FAILED` only after exhaustion | D19; playbook 25 |
| 425 + `Retry-After` for in-flight idempotency | D18; playbook 3 |
| Envelope fields; outbox columns | design §7.1, §7.4, §5.1 |
| txid generation; collision bounded retry; `save` duplicate behavior | E1 spec §6 (`PaymentRepository`), design §4.2 |
| BR Code: own EMV + CRC16 implementation, tested | design §4.2 (Dynamic QR row) |
| Scenarios proven | playbook 1, 2, 3, 4, 15, 25 |

## 5. Exact contracts

### 5.1 `POST /v1/payments`

Request (Content-Type `application/json`; `Authorization: Bearer <key>`; `Idempotency-Key` **required**, 8–200 chars):

```json
{ "amount": 10000, "description": "Order #123", "expiresIn": "PT30M" }
```

- Validation order (fail → `400 invalid_request` with field map): body parses · `amount` integer > 0 ·
  `description` ≤ 140 (optional) · `expiresIn` ISO-8601 duration, `30s ≤ expiresIn ≤ 24h` (optional,
  default `PT30M`) · `Idempotency-Key` present and in range.
- Success — `201 Created`, `Location: /v1/payments/{txid}`, `X-Request-Id` echoed:

```json
{
  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
  "status": "PENDING",
  "amount": 10000,
  "currency": "BRL",
  "expiresAt": "2026-08-28T15:30:00Z",
  "brcode": "00020101021226530014BR.GOV.BCB.PIX0131dargent-dev-receber@example.com5204000053039865406100.005802BR5916Dargent Dev LTDA6009SAO PAULO622905258KD4Z9X2Q7W1M5T3R6Y0A1B2C6304EDD2",
  "expiresIn": "PT30M"
}
```

`expiresAt` in the response is **the PSP's truth** (copied from the create-charge response, §5.7).

#### 5.1.3 Idempotency semantics (binding; playbooks 1–4, 15)

| Situation | Result |
|---|---|
| First call (key unknown) | insert `IN_FLIGHT` row (PK race loser → 425 path) · run the transactional core (§5.8) · on success, update row to `COMPLETED` + snapshot of status/body · answer |
| Same key + same `request_fingerprint` + `COMPLETED` | **snapshot replay**: exact stored status + body, zero side effects; header `Idempotent-Replay: true` |
| Same key + different fingerprint (any state) | `409 idempotency_key_conflict` — non-negotiable (playbook 2) |
| Key `IN_FLIGHT` (concurrent or stuck) | `425 idempotency_key_in_flight` + `Retry-After: 1` (D18). Stuck rows are cleaned at M3 — **declared limitation**: a crashed in-flight key stays 425 until cleanup |
| Core succeeded but PSP phase exhausted → 502 | **no snapshot** — the key row is **deleted** (audit_log keeps the trail); retrying the same key starts a fresh payment. Only 2xx responses are snapshotted |

Fingerprint = lowercase-hex SHA-256 over the **exact request body bytes**.

### 5.2 `GET /v1/payments/{txid}`

`200` with the §5.1 body shape (BR Code recomputed on read; `expiresIn` recomputed from `expiresAt`).
Unknown txid **or another merchant's txid** → `404 payment_not_found` (never 403). D21: straight from the
table, no cache.

### 5.3 `GET /v1/payments?cursor=&limit=`

- Fixed ordering `created_at DESC, txid DESC`; `limit` default 20, values > 100 clamped to 100 (no error);
- `cursor` opaque: `base64url("<txid>|<created_at as epoch-micros>")` of the last returned row; invalid
  cursor → `400 invalid_request` (field map: `cursor`);
- Response: `{ "items": [ …payment summaries… ], "nextCursor": "…" | null }` — summary fields: `txid`,
  `status`, `amount`, `currency`, `createdAt`, `expiresAt`. No `brcode` in list items.

### 5.4 Error contract (all of it lives now; refund/webhook codes dormant until E4/E8)

One `ErrorResponseWriter` bean emits **every** error (global handler, API-key filter, auth entry point,
future HMAC filter). Body: RFC 9457 `application/problem+json`, fields `type` (`about:blank`), `title`,
`status`, `code` (machine), `detail` (safe), and `fields` (field→message map) for validation errors.
`500 internal` never leaks the exception message; logs method+URI+exception. `NoResourceFoundException`
→ canonical 404 (never 500). Domain mapping: `InvalidTransitionException` → `409 invalid_transition`.

**Catalog addition (E3's only one):** `psp_unavailable` · **502** · "PSP unreachable/unhealthy after
creation retries; payment persisted as FAILED". S8 syncs design §6.3 with this row.

`X-Request-Id`: accepted (validated `[A-Za-z0-9-]{8,64}` — invalid → generated, never rejected), generated
(UUID) when absent, echoed in every response, propagated to MDC and into the outbox envelope's `requestId`.

### 5.5 BR Code composer (pure, `payments` domain)

`BrCode.of(pixKey, receiverName, receiverCity, amountCents, txid)` → EMV payload string.

| TLV | Content |
|---|---|
| 00 | `01` |
| 01 | `12` (dynamic QR — single use) |
| 26 | `0014BR.GOV.BCB.PIX` + `01<len>+pixKey` |
| 52 / 53 / 54 / 58 | `0000` · `986` · amount as `#.#` decimal string · `BR` |
| 59 / 60 | receiverName (≤ 25) · receiverCity (≤ 15) |
| 62 | `05<len>+txid` (Bacen cap 25) |
| 63 | `6304` + **CRC16-CCITT-FALSE** (poly `0x1021`, init `0xFFFF`) over all preceding bytes, uppercase hex |

**Golden vector — asserted byte-exact by tests (recomputed independently before being written here):**

```
pixKey        = dargent-dev-receber@example.com
receiverName  = Dargent Dev LTDA
receiverCity  = SAO PAULO
amountCents   = 10000
txid          = 8KD4Z9X2Q7W1M5T3R6Y0A1B2C
brcode        = 00020101021226530014BR.GOV.BCB.PIX0131dargent-dev-receber@example.com5204000053039865406100.005802BR5916Dargent Dev LTDA6009SAO PAULO622905258KD4Z9X2Q7W1M5T3R6Y0A1B2C6304EDD2
(length 174; CRC EDD2)
```

Rationale for composing from API config, not PSP response: receiver identity is **our** credential data
(a real PSP integration keeps it client-side); composition must not depend on PSP field availability.
The simulator's `pix*` response fields are informational in E3.

### 5.6 Outbox row + envelope (E3 writes rows; the relay is E6)

On success, the transactional core inserts one `payments.outbox` row:

```json
{ "eventId": "<uuid>", "type": "payment.created", "version": 1,
  "aggregateId": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C", "merchantId": "<uuid>", "requestId": "<x-request-id>",
  "occurredAt": "2026-08-28T15:00:00Z",
  "payload": { "txid": "…", "merchantId": "…", "amount": 10000, "description": "Order #123", "expiresAt": "…" } }
```

Serialized **once** into `payload` (jsonb) by the shared Jackson-3 serializer. `eventId` = the aggregate's
drained `PaymentCreated` bound into the envelope. `next_attempt_at` = `occurredAt`; `attempt_count` = 0.

### 5.7 PSP phase (after commit) — binding to the E2 contract

`PspPort.createCharge(txid, amountCents, expiresAt, callbackUrl, description)` → adapter performs
`POST {PSP_BASE_URL}/cobs` with the **exact request/response of E2 spec §5.1** (txid, amount, expiresAt,
callbackUrl = `PSP_CALLBACK_URL`, description). Timeouts connect 2 s / read 5 s. Retry policy (D19):
up to `PSP_CREATE_MAX_ATTEMPTS` attempts, linear backoff `base × attempt` via an **injected sleeper**
(never real `Thread.sleep` in tests); retryable = connect/read errors, 5xx; **409 `txid_already_exists`
is NOT retryable** → treated as already-created success path (read the charge back via `GET /cobs/{txid}`).

- Success → second short tx: conditional `UPDATE payments SET expires_at` (PSP truth wins); response 201.
- Exhaustion → second tx: `payment.markFailed("psp_create_exhausted")` via
  `updateIfVersionMatches` (E1 port; lost race = a webhook got there first — re-read and decide) +
  `PaymentFailed` outbox row; answer `502 psp_unavailable`.

### 5.8 Transactional core (the whole epic in one paragraph)

One transaction: insert `idempotency_keys` row `IN_FLIGHT` (PK violation → loser 425 path) →
`Payment.create(txid, merchantId, amount, description, expiresAtRequested, now)` → `PaymentRepository.save`
(duplicate txid → bounded regeneration, 3 attempts, then `500 internal` — practically impossible) →
insert `outbox` row (§5.6) → insert `audit_log` row (`command_name=create_payment`, `actor_key_id`,
`merchant_id`, `aggregate_id=txid`, `request_id`) → commit. **Then** the PSP phase (§5.7) — never inside
the transaction. On core success: update idempotency row to `COMPLETED` + snapshot **in the PSP-phase
transaction**, not the core one (a crash between core and PSP leaves `IN_FLIGHT` → 425 → declared
limitation, M3 cleanup).

### 5.9 API keys (design §8.1 verbatim + decisions)

Format `^psp_(test|live)_[A-Za-z0-9]{43}$`. Storage: `key_hash` = lowercase-hex SHA-256 of the raw key
UTF-8 (no salt — 256-bit entropy keys; constant-time `MessageDigest.isEqual` against the presented key's
hash); `key_prefix` = first 16 chars (indexable lookup). Revoked keys → 401. `Authorization: Bearer` only
(scheme case-insensitive, key case-sensitive); missing/malformed → `401 unauthorized` via
`ErrorResponseWriter`. **Dev seeding:** `dev` profile + `DARGENT_DEV_API_KEY` set → provisioner upserts
(hash only — the raw key lives in env, never in the DB, never in logs). `SecurityConfig` in `apps/api`:
`/v1/**` authenticated; `/webhooks/psp` `permitAll` (E4 adds the HMAC filter); actuator health/info open.

## 6. Concurrency & races

| Race | Arbitration | Proof |
|---|---|---|
| Two concurrent creates, same key | PK violation on `idempotency_keys` insert; loser re-reads → `IN_FLIGHT` → 425 | scenario 15 IT (barrier, 4 threads: exactly one 201, others 425, exactly one payment row) |
| txid collision (SecureRandom 25 alnum) | `save` duplicate → regenerate, ≤ 3 attempts | unit test with a fake repository that throws once |
| PSP create already exists (409 from simulator) | not retryable → read-back path | WireMock IT |
| `markFailed` lost race | conditional `updateIfVersionMatches`; loser re-reads | unit test (fake returns false → re-read decides) |

## 7. Testing requirements

- **Unit (no Spring):** `BrCodeTest` (golden vector byte-exact + CRC self-check property + sanitization
  rejects) · `CursorCodecTest` (roundtrip, ordering key) · fingerprint hashing · `CreatePaymentUseCaseTest`
  (fakes for every port; D19 exhaustion path; idempotency table as a state machine).
- **Slice/IT (full-context MockMvc — `@WebMvcTest` does not exist in Boot 4.1; E2's declared deviation is
  the house pattern):** PG16 Testcontainers (module-shared singleton, E1 pattern) + WireMock on a dynamic
  port for the PSP. Assert: auth 401/404 paths, validation field maps, scenario 1 (replay byte-equal,
  exactly one row), 2 (conflict), 3 (425 + header), 4 (snapshot zero new side effects), 15 (concurrent),
  25 (3 WireMock requests recorded, backoff via recorded sleeper, FAILED + 502 + `PaymentFailed` outbox row),
  cross-tenant 404, cursor pagination (seed 25, walk, stability under insertion), `NoResourceFound` → 404.
- Zero `Thread.sleep` in tests (sleeper is injected and recorded). Awaitility for nothing here — the flow
  is synchronous by design.
- Coverage: per-module floors maintained (payments floor measured post-IT, lesson #8).

## 8. Risks & troubleshooting

| Risk | Likelihood | Mitigation |
|---|---|---|
| Jackson 3 import confusion (`com.fasterxml` vs `tools.jackson`) | High (fresh lesson) | lesson #13; grep gate in S9: no `com.fasterxml.jackson` imports in prod sources |
| `@WebMvcTest` missing → wasted hours | Certain if tried | do not attempt; full-context MockMvc (E2 deviation) |
| WireMock static port collisions in CI | Medium | dynamic ports only; reset per test |
| Real sleeps in backoff tests | Tempting | sleeper injected + recorded; policy numbers asserted, never waited on |
| 425 IT flaky under pool contention | Medium | `CyclicBarrier` before requests (E1 race-IT pattern); assert counts, not identities |
| Stuck `IN_FLIGHT` keys in dev after crashes | Certain over time | declared limitation (§5.1.3); new key in dev; cleanup job M3 |
| `description` drift confusion | Low | S0 verifies V102; V107 header tells the story; design §5.1 row restored in S8 |
| Cursor pagination instability under inserts | Medium | keyset predicate on `(created_at, txid)`; determinism test with fixed clock |

## 9. Closure checklist (epic DoD)

- [ ] §5.1–§5.3 contracts byte-shape exact vs design §6.2–§6.4; §5.5 golden vector asserted
- [ ] Scenarios 1, 2, 3, 4, 15, 25 evidenced in `tasks/e3-acceptance-matrix.md` (zero pending)
- [ ] Outbox row + envelope exact (§5.6); `PENDING` after core, `FAILED` path per D19 proven
- [ ] `mvn -B verify` green; CI green on `main`; boundary script green; scope diff
      (`apps/psp-simulator`, `modules/ledger`, `modules/notifications`) = 0
- [ ] No `com.fasterxml.jackson` imports in prod sources (lesson #13 gate)
- [ ] Docs synced: design §6.3 (`psp_unavailable` row) + §5.1 (`description` row restored if V107 ran) ·
      README honesty callout updated (**create works now; webhook step still E4**) · CHANGELOG · ledger E3 ✅
- [ ] `.env.example` gains `PSP_*`, `DARGENT_*` and the E2 follow-up `CHAOS_*` entries
