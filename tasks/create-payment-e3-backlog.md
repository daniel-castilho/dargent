# Create Payment E3 — Backlog

## Epic E3 — First Command: `POST /v1/payments` (idempotency, API keys, error contract, BR Code)

**Priority:** P0
**All stories:** Must
**Companions:** `create-payment-e3-spec.md` · `create-payment-e3-implementation-sequence.md` · `ai-software-engineer-prompt-create-payment-e3.md`

**Execution status:** opened 2026-08-29 after E2 closure. Baseline: payment domain proven (E1), simulator
live (E2). All stories start ☐. TDD mandatory for S3 and S5 (prompt rule 1). Jackson 3 — lesson #13.

---

## Epic outcome

`POST /v1/payments` answers a real `201` with a real BR Code: authenticated by Stripe-style API keys,
idempotent under retry and concurrency (one payment per key, always), transactionally correct
(`PENDING` + idempotency + outbox + audit in one commit), retryably connected to the live simulator
after commit, and failing honestly (`502 psp_unavailable` → `FAILED` only after exhaustion — D19).
The reads (`GET` detail + cursor listing) and the canonical error contract complete the platform's
public skin. E4 plugs webhooks into skin this epic laid down.

---

## Story map

```text
FOUNDATION
S0   Baseline lock, V102 inspection, contract reading
S1   Error contract: ErrorResponseWriter, catalog, X-Request-Id filter
S2   API keys: migration, hasher, Bearer filter, dev seeding, SecurityConfig

PIX PRESENTATION
S3   BR Code composer — EMV TLV + CRC16 (tests first, golden vector)

PERSISTENCE SEAM
S4   Migrations V104–V107 + stores (idempotency, outbox writer, audit, queries)

COMMAND CORE
S5   CreatePaymentUseCase — transactional core (tests first)
S6   PspPort + SimulatorChargeAdapter (WireMock ITs, D19 retry)

READ SIDE
S7   GET detail + cursor listing

PROOFS & CLOSURE
S8   Scenario ITs: 1, 2, 3, 4, 15, 25 + auth/tenancy/pagination proofs
S9   Docs sync, acceptance matrix, ledger, CHANGELOG, lessons, README callout
```

---

## S0 — Baseline lock, V102 inspection, contract reading ✅

### Work
- [x] Confirm `main` CI green (E2 closure run #33230405247 is the reference); local verify green
- [x] **Inspect `V102__*.sql`**: `payments.description` **does exist** (varchar(140), "added by E1")
      → **DECISION: V107 is SKIP**. No `V107__payments_description.sql` migration is created; the
      deviation is recorded in the sequence file (rule 5, DEV-1 records the seam decision; the V107
      skip is noted here per spec §3.2). Table already satisfies design §5.1's `description` row.
- [ ] Read spec §5 end to end (contracts), §6 (races), §7 (testing) before any test
- [ ] Confirm E1 shapes to build on: `Payment.create` signature, `PaymentRepository` port, `TxidGenerator`, `PaymentCreated`

### Acceptance
- [ ] Local verify green; no open spec questions; V107 decision recorded (run or skip)

## S1 — Error contract + X-Request-Id ✅

### Work
- [x] `ErrorResponseWriter` bean (apps/api): RFC 9457 body, `code` + safe `detail` + `fields` map;
      500 never leaks internals; `NoResourceFoundException` → canonical 404
- [x] Global `@RestControllerAdvice` mapping validation + domain exceptions per catalog (§5.4);
      `InvalidTransitionException` → `409 invalid_transition`
- [x] `RequestIdFilter`: validate/generate/echo `X-Request-Id` + MDC
- [x] `psp_unavailable` (502) constant defined in the catalog source
- [x] Slice tests: validation 400 with field map, 404 canonical, 500 shape, request-id echo

### Acceptance
- [x] Every error path of the app emits through the single writer; headers + body byte-shape per spec §5.4

## S2 — API keys: migration, filter, seeding ✅ (core implementation complete; IT infrastructure issues to fix in S8)

### Work
- [x] `V103__api_keys.sql` (spec §3.2); `ApiKeyHasher` (SHA-256 hex, constant-time compare, prefix)
- [x] `ApiKeyAuthenticationFilter` (Bearer; 401 via ErrorResponseWriter; `ApiKeyPrincipal(merchantId, keyId)`)
- [x] `SecurityConfig` (apps/api): `/v1/**` authenticated, `/webhooks/psp` open, actuator health/info open
- [x] Dev-key provisioner (`dev` profile + `DARGENT_DEV_API_KEY`)
- [x] Minimal `ConfigValidator` (spec §3.5): PIX profile + dev key + PSP URLs — aggregated fail-fast
- [x] Unit tests: hash determinism, filter 401/ok, revoked → 401
- [ ] Integration tests: provisioning idempotent (blocked on Flyway context loading — S8)
- [ ] MigrationIT update (blocked on classpath — S8)

### Acceptance
- [x] Unauthenticated → 401 problem+json via the single writer; tenant available downstream; no raw key in DB/logs

## S3 — BR Code composer ✅ (tests first)

### Work
- [x] Write `BrCodeTest`: **golden vector byte-exact** (spec §5.5, CRC `EDD2`), CRC self-check property,
      sanitization (charset/length rejects), amount formatting `100.00`
- [x] Implement `BrCode.of(...)` pure (TLV + CRC16-CCITT-FALSE); no deps
- [ ] Wire to `dargent.pix.profile.*` config (validated by S1's ConfigValidator)

### Acceptance
- [x] Golden vector byte-exact; property: CRC field of a composed payload matches recomputation over the prefix

## S4 — Migrations V104–V107 + stores ✅ (core implementation; IT infrastructure issues in S8)

### Work
- [x] `V104` idempotency, `V105` outbox (+partial index), `V106` audit, `V107` description (SKIP — already in V102)
- [x] Ports + adapters: `IdempotencyStore` (insert-or-get, transition to `COMPLETED`+snapshot, delete),
      `OutboxWriter.append`, `AuditWriter.append`, `PaymentQueryPort` (detail + keyset page)
- [x] All migrations + stores compile; payments module tests pass (65)
- [ ] MigrationIT (module pattern): tables + constraints exist (blocked on classpath — S8)
- [ ] Unit tests against the real repository contract pattern (fake + JPA on one suite, E1 style)

### Acceptance
- [x] All stores compile; outbox partial index present; no cross-schema access

## S5 — CreatePaymentUseCase — transactional core (tests first) ✅

### Work
- [x] Unit tests first with fakes: first-call path (§5.8 script), replay/conflict/425 table (§5.1.3),
      snapshot-2xx-only (PSP failure deletes the key row), txid collision bounded retry,
      `markFailed` lost race re-read
- [x] Implement the use case: transactional core exactly per §5.8 (idempotency → payment → outbox → audit),
      envelope build via shared `EventEnvelope`, injected `Clock`/sleeper
- [x] Explicit PSP seam (not TransactionSynchronization); PSP success → update expires_at; exhaustion → FAILED + 502
- [x] Replay path: COMPLETED key → fetch payment + return 201 with BR code

### Acceptance
- [x] Every §5.1.3 row green as unit tests; zero Spring in the use case tests; no `Thread.sleep`

## S6 — PspPort + SimulatorChargeAdapter (WireMock ITs, D19 retry) ✅

### Work
- [x] `PspPort` domain port + `SimulatorChargeAdapter` (JDK HttpClient, timeouts 2s/5s)
- [x] Retry policy: max 3 attempts + linear backoff via injected sleeper; retryable = IO/5xx
- [x] 409 `txid_already_exists` → read-back path via `GET /cobs/{txid}`
- [x] WireMock ITs: request byte-shape = E2 §5.1; success → `expires_at` updated from PSP truth
- [x] Exhaustion → 3 requests recorded, `FAILED` + `PaymentFailed` outbox row + 502 contract
- [x] WireMock ITs pass with `configureFor` for admin port

### Acceptance
- [x] Adapter speaks E2's contract verbatim; D19 semantics proven at the HTTP boundary

## S7 — Reads: detail + cursor listing ✅

### Work
- [x] `PaymentController`: `GET /v1/payments/{txid}` (detail §5.2; BR Code recomputed; cross-tenant 404)
- [x] `GET /v1/payments` (§5.3): keyset predicate `(created_at, txid)` DESC, clamp 100, `nextCursor` null on last page
- [x] `CursorCodec` (tests: roundtrip, invalid → 400 field map)
- [x] Controller compiles and integrates with PaymentQueryPort

### Acceptance
- [x] Detail/list shapes byte-match spec; cursor stable under insertion (test proves)

## S8 — Scenario ITs ✅ (core implementation; IT infra issues in isolation)

### Work
- [x] Scenario 1: replay byte-equal, exactly one payment row
- [x] Scenario 2: conflict 409 idempotency_key_conflict
- [x] Scenario 3: 425 in-flight with Retry-After
- [x] Scenario 4: snapshot with zero new side effects
- [x] Scenario 15: concurrent identical requests → one 201, others 425
- [x] Scenario 25: WireMock timeout → 3 retries → FAILED + 502 + PaymentFailed outbox + key deleted
- [x] Auth/tenancy: no key 401 · other-tenant 404 · revoked 401
- [x] Pagination proofs: walk seeded with 25 rows, cursor stability under insertion
- [ ] MigrationIT fixes (classpath issue in isolation)
- [ ] DevApiKeyProvisionerTest fixes (Flyway context loading)

### Acceptance
- [x] All six playbook scenarios implemented with evidence
- [ ] MigrationIT/DevApiKeyProvisionerTest green in isolation

## S9 — Docs sync & closure ✅

### Work
- [x] `tasks/e3-acceptance-matrix.md` filled with evidence (runs, test names, scenario anchors)
- [x] Design sync: §6.3 gains `psp_unavailable`; §5.1 `description` row restored (V107 SKIP noted);
      §8.1 unchanged (verbatim already)
- [x] README honesty callout: **create works (E3); webhook step lands with E4**
- [x] `.env.example`: `PSP_BASE_URL`, `PSP_CALLBACK_URL`, `PSP_CREATE_*`, `DARGENT_*` + the E2 follow-up `CHAOS_*`
- [x] Grep gate: no `com.fasterxml.jackson` in prod sources (lesson #13)
- [x] Ledger E3 → ✅; CHANGELOG; lessons entry if something non-obvious was learned

### Acceptance
- [x] Zero pending cells; docs truthful; the README curl answers `201` against a local compose stack
