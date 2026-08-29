# PSP Simulator API E2 — Technical Specification

## Epic E2 — The Fake Stripe: Charges, Payer Bank, Signed Webhooks & Chaos Knobs

**Priority:** P0
**Companions:** `psp-simulator-e2-backlog.md` · `psp-simulator-e2-implementation-sequence.md` · `ai-software-engineer-prompt-psp-simulator-e2.md`
**Baseline:** E0+E1 closed (CI run #33225043138 green). Greenfield epic inside `apps/psp-simulator` only.

---

## 1. Purpose

Build the outside world Dargent will defend against: a simulated PSP whose charges, payer bank and signed
webhooks behave like the real thing — and whose chaos knobs make the API's idempotency, dedupe,
resurrection and reconciliation guarantees *provably* testable rather than aspirational. Everything E3–E5
builds on the contracts in this document.

The simulator is a deliberate **anti-corruption exercise in reverse**: the platform side (E4) must implement
webhook validation against THIS spec, never against shared code — exactly how a real PSP integration works.

---

## 2. Scope

### In scope
- Charge lifecycle: `POST /cobs`, `GET /cobs/{txid}`, `POST /cobs/{txid}/payments` (§5);
- In-memory `ChargeStore`, `Charge` domain rules, `endToEndId`/`eventId` generators;
- HMAC-SHA256 webhook signer + async single-attempt delivery engine (§5.4);
- Chaos: webhook duplicate / delay / drop-rate, PSP error-rate / latency (§6);
- Endpoint slice tests, wire-level webhook IT with a stub receiver, chaos behavior tests.

### Out of scope
- Any change to `apps/api`, `modules/*`, CI, compose topology (defaults stay all-off);
- BR Code generation (the API composes it in E3 from the PIX fields this API returns);
- Refunds, reconciliation beyond `GET /cobs/{txid}`, callbacks for refunds (E8 extends the simulator);
- Persistence of any kind; authentication; metrics beyond actuator health.

---

## 3. Architectural constraints

### 3.1 Isolation

- Package root `io.dargent.pspsimulator`; **zero** `io.dargent.*` imports beyond it (boundary script
  enforces). Own signer, own generators, own error payload — duplication with the platform is intentional
  (independent implementations of a shared *specification*, like real integration partners).
- No database, no Flyway, no messaging clients. State: `ConcurrentHashMap` only. Restart wipes charges —
  documented contract (the simulator's durability is nobody's problem but its own).

### 3.2 Config surface (M0-compatible names — keep stable)

| Property (yaml) | Env | Default |
|---|---|---|
| `server.port` | — | 8090 |
| `dargent.psp.webhook-secret` | `PSP_WEBHOOK_SECRET` | `dev-only-secret` |
| `dargent.psp.profile.pix-key` | `PSP_PIX_KEY` | `dargent-dev-receber@example.com` |
| `dargent.psp.profile.receiver-name` | `PSP_RECEIVER_NAME` | `Dargent Dev LTDA` |
| `dargent.psp.profile.receiver-city` | `PSP_RECEIVER_CITY` | `SAO PAULO` |
| `dargent.psp.chaos.webhook-duplicate` | `CHAOS_WEBHOOK_DUPLICATE` | `false` |
| `dargent.psp.chaos.webhook-delay-ms` | `CHAOS_WEBHOOK_DELAY_MS` | `0` |
| `dargent.psp.chaos.webhook-drop-rate` | `CHAOS_WEBHOOK_DROP_RATE` | `0.0` |
| `dargent.psp.chaos.psp-error-rate` | `CHAOS_PSP_ERROR_RATE` | `0.0` |
| `dargent.psp.chaos.psp-latency-ms` | `CHAOS_PSP_LATENCY_MS` | `0` |
| `dargent.psp.chaos.seed` | `CHAOS_SEED` | unset (random) |

### 3.3 Dependency additions (locked — nothing else)

None required. `spring-boot-starter-web` (RestClient), actuator, starter-test are already in the M0 pom.
WireMock (`org.wiremock:wiremock-standalone`, test scope) is the single pre-approved candidate if the S7
stub receiver prefers it over a test-local `@RestController` — pick one approach and document; do not mix.

---

## 4. Decision map (traceability)

| Spec element | Source |
|---|---|
| Simulator = outside world, shares no code | AGENTS.md §2, design.md §3.2 |
| Merchant owns the txid | design.md §4.2 (txid is app-generated, 25 alnum, unique); the simulator validates shape + uniqueness |
| `endToEndId` format | design.md §4.2, E1 `EndToEndId` VO regex `E[A-Za-z0-9]{31}` (32 total — the binding artifact, closed in E1) |
| PSP owns charge truth incl. expiry rejection | D6 discussion: resurrection comes from delayed/dropped webhooks, not late payments |
| Chaos knobs | design.md §12, M0 `.env.example` |
| Single-attempt webhook delivery, reconciler recovers | D6, E5 reconciler design; real PSP practice |
| Signature scheme | design.md §6.2, §8.2 |

---

## 5. Exact contracts

### 5.1 `POST /cobs` — create charge

Request:

```json
{
  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
  "amount": 10000,
  "expiresAt": "2026-08-29T01:30:00Z",
  "callbackUrl": "http://api-blue:8080/webhooks/psp",
  "description": "Order #123"
}
```

- Validations (order fixed): txid `^[A-Z0-9]{25}$` → else `400 invalid_txid`; `amount` integer > 0 →
  `400 invalid_amount`; `expiresAt` parseable RFC 3339 in the future (by simulator clock) → `400 invalid_expiry`;
  `callbackUrl` absolute http(s) → `400 invalid_callback_url`; txid absent from the store → `201` (a racing
  duplicate loses the `putIfAbsent`
  race loses → `409 txid_already_exists`).
- `201 Created` response:

```json
{
  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
  "status": "OPEN",
  "amount": 10000,
  "expiresAt": "2026-08-29T01:30:00Z",
  "callbackUrl": "http://api-blue:8080/webhooks/psp",
  "description": "Order #123",
  "pixKey": "dargent-dev-receber@example.com",
  "receiverName": "Dargent Dev LTDA",
  "receiverCity": "SAO PAULO"
}
```

- The three `pix*`/`receiver*` fields are the simulator profile — the raw material the API's BR Code
  composer (E3) consumes.

### 5.2 `GET /cobs/{txid}` — the charge's truth (reconciler's endpoint in E5)

```json
{
  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
  "status": "PAID",
  "amount": 10000,
  "expiresAt": "2026-08-29T01:30:00Z",
  "endToEndId": "E9040381234567890123456789012345",
  "paidAt": "2026-08-29T00:41:12Z"
}
```

- `status`: `OPEN`, `PAID`, or `EXPIRED` (computed: `OPEN` and `now > expiresAt`). `PAID` is permanent —
  a paid charge is never EXPIRED regardless of time. Unknown txid → `404 cob_not_found`.

### 5.3 `POST /cobs/{txid}/payments` — the payer bank

No body. Rules (evaluation order fixed):

| Condition | Result |
|---|---|
| Unknown txid | `404 cob_not_found` |
| `status == PAID` | `409 already_paid` — body is the standard error payload; the original `endToEndId` is recovered via `GET /cobs/{txid}` (paying twice is impossible; reading twice is fine) |
| `status == OPEN` and `now > expiresAt` | `409 charge_expired` |
| else | charge → `PAID`; `endToEndId = generate()`; `paidAt = now`; webhook dispatched (§5.4); `200` |

Success response:

```json
{ "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C", "status": "PAID", "endToEndId": "E9040381234567890123456789012345", "paidAt": "2026-08-29T00:41:12Z" }
```

Error payload everywhere: `{ "code": "<machine_code>", "message": "<human>" }` — the simulator's own shape
(the API's anti-corruption layer maps it; no problem+json here).

### 5.4 Webhook contract — **binding for E2 (signing) and E4 (validating)**

Delivery: `POST` to the charge's `callbackUrl`.

| Element | Value |
|---|---|
| Content-Type | `application/json` |
| `X-PSP-Timestamp` | Unix epoch seconds as string (delivery time) |
| `X-PSP-Signature` | lowercase hex of `HMAC-SHA256(secret, timestamp + "." + rawBody)` — UTF-8 bytes, `.` literal separator, body signed is the **exact bytes sent** (no re-serialization) |
| Body | single JSON object, exact field set below |

```json
{
  "eventId": "psp-evt-3f2b9c1e-8a4d-4e2a-9b1c-7d5f0a6e8c9d",
  "type": "payment.confirmed",
  "txid": "8KD4Z9X2Q7W1M5T3R6Y0A1B2C",
  "endToEndId": "E9040381234567890123456789012345",
  "amount": 10000,
  "paidAt": "2026-08-29T00:41:12Z"
}
```

- `eventId` format `psp-evt-<uuid4>`; **stable per payment** — the duplicate knob re-delivers the SAME
  `eventId` (that is what makes it dedupe fuel, not a second payment).
- One delivery per dispatch attempt; receiver response status is ignored (single attempt, no retry — the
  reconciler is the recovery story, E5).
- Delivery timeouts: connect 2 s, read 5 s; failed/timeout deliveries are logged (`WARN`) and dropped —
  they are indistinguishable from `drop` chaos by design.

**Shared test vector** (asserted verbatim by tests on BOTH sides — E2 signer, E4 validator):

```
secret    = dev-only-secret
timestamp = 1787932800
body      = {"eventId":"psp-evt-test-001","type":"payment.confirmed","txid":"8KD4Z9X2Q7W1M5T3R6Y0A1B2C","endToEndId":"E9040381234567890123456789012345","amount":10000,"paidAt":"2026-08-29T00:00:00Z"}
canonical = 1787932800.{"eventId":"psp-evt-test-001",...same body...}
signature = 549eabc4c6f862fdb9322861f43091039de9c75de8107a60945d464755549113
```

---

## 6. Chaos semantics (deterministic in tests)

| Knob | Semantics | Test strategy |
|---|---|---|
| `webhook-duplicate` (bool) | Each successful payment's event is delivered **twice** (same `eventId`) | `=true` → assert 2 deliveries |
| `webhook-delay-ms` (int ≥ 0, cap 30 000) | Delivery scheduled after the delay (shared scheduler) | `=100` in tests → none before, one after (Awaitility) |
| `webhook-drop-rate` (0.0–1.0) | Per-delivery discard probability, seeded `Random` | `=1.0` → 0 deliveries; `=0.0` → all delivered. No mid-probability assertions |
| `psp-error-rate` (0.0–1.0) | Per-endpoint-call 503 `psp_unavailable` probability | Forced `1.0`/`0.0` at filter level only |
| `psp-latency-ms` (int ≥ 0, cap 30 000) | Sleep before endpoint handling (the ONE sanctioned production sleep) | Not asserted on timing in tests; only that it's applied |

Interaction order: `latency` → `error-rate` → handler → dispatch (`delay` → `drop` → `duplicate` per
delivery). Defaults keep every knob off — the M0 compose contract (`all off`) must keep passing untouched.

---

## 7. Testing requirements

- Pure unit layer first (Charge rules, generators, signer vector) — no Spring.
- MockMvc slices for endpoint validation branches and error codes.
- One lifecycle IT over HTTP with a captured-delivery stub receiver; the receiver **recomputes** the
  signature from captured raw bytes + timestamp (the exact procedure E4 will implement — reuse this test's
  assertion shape there).
- Awaitility for everything async (poll interval ≥ 50 ms, timeouts ≥ 5 s); zero sleeps in assertions.
- Chaos behavior tests at dispatcher/filter level with forced modes; endpoint ITs always run with knobs off.
- The simulator keeps no ArchUnit suite (module rules don't apply) — the boundary script import check is
  its net.

---

## 8. Risks & troubleshooting

| Risk | Likelihood | Mitigation |
|---|---|---|
| Signature mismatch on the wire | High (classic) | Body signed must be body sent — serialize once, keep the bytes; UTF-8 everywhere; the vector test catches canonicalization drift immediately |
| Duplicate deliveries race into either order | Certain (async) | Allowed by contract; receivers dedupe by `eventId` (E4); tests assert counts, never order |
| Executor starvation under chaos delay | Low | Bounded pool sized ≥ 4; delay is schedule-based, not sleep-per-thread; document pool size |
| Clock leakage (`Instant.now()` scattered) | Medium | S1 rule + review; expiry tests time-travel via parameters |
| Scope creep into the platform | Medium | Sequence Step 7 scope check (`git diff --stat main -- apps/api modules` empty) |
| Callback URL points at a non-listening port (compose) | Expected behavior | Delivery fails, logged WARN, dropped — the reconciler story; do not add retries to "fix" it |

## 9. Closure checklist (epic DoD)

- [ ] §5 contracts implemented byte-shape exact; §5.4 vector asserted in E2 tests (and mirrored in E4 later)
- [ ] All five chaos knobs wired and behavior-tested; defaults all-off (M0 compose contract intact)
- [ ] `mvn -B verify` green; CI green on `main`; boundary script green (no platform imports)
- [ ] `tasks/e2-acceptance-matrix.md` zero pending; epics ledger E2 ✅; CHANGELOG; design.md §12 synced if drift
- [ ] Zero diff against `apps/api` and `modules/` — the outside world stayed outside
