# Webhook Intake E4 — Technical Specification

## Epic E4 — `POST /webhooks/psp`: fail-closed HMAC, anti-replay, dedupe, confirmation

**Priority:** P0 — closes the money loop: create (E3) → pay at the simulator (E2) → webhook → `CONFIRMED`
**Companions:** `webhook-intake-e4-backlog.md` · `webhook-intake-e4-implementation-sequence.md` · `ai-software-engineer-prompt-webhook-intake-e4.md`
**Baseline:** E2 closed (contract §5.4 + shared vector). E3 implemented (matrix zero pending) but **CI red on
its closure commits — runs #10/#11 FAILED; main MUST be green before this epic's Step 0 gate**.

---

## 1. Purpose

The PSP calls us. Everything about this endpoint is adversarial by default: the signature may lie, the
timestamp may be stale, the event may be a duplicate, a replay, or bait for an unknown payment. The intake
is **fail-closed** (any doubt → 401, but the raw payload is ALWAYS persisted first — attack audit), and the
happy path is the project's first full money loop: the webhook's `payment.confirmed` drives E1's
`confirm()` transition with the fee breakdown and leaves a `PaymentConfirmed` row in the outbox (delivery
is E6). The **shared HMAC test vector of E2 §5.4 now meets its validator side** — asserted byte-exact here
as it is in the simulator's `WebhookSignerTest`.

## 2. Scope

### In scope
- `POST /webhooks/psp`: raw capture → signature validation (fail-closed) → raw persistence → processing;
- `WebhookSignatureValidator` (pure domain, TDD) + anti-replay window (5 min, injected `Clock`);
- `V108__webhook_events.sql` + `WebhookEventStore` port/adapter;
- `WebhookIntakeUseCase` (TDD): dedupe (`provider_event_id`), sanity guards, confirmation via E1's
  conditional UPDATE, `PaymentConfirmed` outbox row, audit row;
- Scenarios 6, 7, 8, 10 over HTTP + hermetic full-loop IT (create → signed webhook → `CONFIRMED`);
- README honesty callout **full flip** (the loop closes; no more target-flow warning).

### Out of scope
- Retries of any kind (the PSP delivers once — E2 contract; responses teach it nothing);
- Reconciler, expiration scheduler, resurrection policies (E5 — the domain's `EXPIRED→CONFIRMED` late
  transition is naturally supported, nothing extra to build);
- Relay/publisher (E6), consumer-side dedupe by `eventId` (E6/E10), notifications (E10);
- Rate limiting, IP allowlists (M4 hardening); any change to `apps/psp-simulator` or other modules;
  CI workflow changes — **except the fix for the currently red E3 runs, which gates Step 0**.

## 3. Architectural constraints

### 3.1 Package shape

```
modules/payments
├── domain/model/          + WebhookSignatureValidator (pure; bytes in, verdict out)
├── application/           + WebhookIntakeUseCase (the one transaction)
├── adapter/in/webhook/    + WebhookController (raw body capture, header extraction, outcome mapping)
├── adapter/out/persistence/ + WebhookEventStoreAdapter (+ existing outbox/audit writers reused)
apps/api                   + nothing new (SecurityConfig already permits /webhooks/psp; ErrorResponseWriter reused)
```

**Endpoint-driven intake, not a servlet filter** (declared interpretation of design §8.2 "fail-closed"):
the raw payload must be persisted on the 401 paths too, and the processing must be one testable use-case
transaction — a servlet filter cannot do either cleanly. The controller captures the raw bytes ONCE and
hands them to the use case; fail-closed behavior is proven by tests, not by chain position.

### 3.2 Config

| Property | Env | Default |
|---|---|---|
| `dargent.psp.webhook-secret` | `PSP_WEBHOOK_SECRET` | `dev-only-secret` |

Same env name and dev default as the simulator (compose passes the same value to both apps — the dev
"shared secret" is literal). Anti-replay window is a **named constant** (300 s), not config.

### 3.3 Dependencies

None. Jackson 3 (`tools.jackson.*`) per lesson #13; raw body handling is plain servlet/`byte[]`.

## 4. Decision map (traceability)

| Spec element | Source |
|---|---|
| Signature scheme `ts + "." + rawBody`, UTF-8, lowercase hex, exact bytes | E2 spec §5.4 (the binding contract) · design §8.2 |
| Shared test vector asserted byte-exact | E2 spec §5.4 (`549eabc4…9113`) — both sides, by construction |
| Fail-closed + raw persisted even on invalid signature | AGENTS §4.4; playbook 6 |
| Anti-replay 5 min → `signature_expired` | design §6.3, §8.2; playbook 7 |
| Dedupe key `provider_event_id = endToEndId + type` | design §5.1 `webhook_events` |
| `payload_raw` immutable; replay produces same result | design §5.1; playbook 10 |
| Unknown/foreign types → `IGNORED`, never crash | design §7.2 UNKNOWN policy |
| Confirmation = E1 `confirm(endToEndId, FeeBreakdown, when)` via conditional UPDATE | E1 spec §5.2; AGENTS §3.2 |
| Fee = 100 bps, merchant-favorable floor | D7, E1 `FeeBreakdown`/`BpsRate` |
| `PaymentConfirmed` → outbox envelope `payment.confirmed` | design §7.1; E3's outbox writer |
| Scenarios proven | playbook 6, 7, 8, 10 |

## 5. Exact contracts

### 5.1 `POST /webhooks/psp` — pipeline (order is binding)

Request: `Content-Type: application/json`, headers `X-PSP-Timestamp` (epoch-seconds string),
`X-PSP-Signature` (lowercase hex), raw body = the exact bytes (captured once — never re-serialized).

| Step | Rule | Outcome |
|---|---|---|
| 1. Capture | read raw body bytes + headers | — |
| 2. Validate (§5.2) | missing/malformed headers or signature mismatch | persist raw (`signature_valid=false`) → `401 invalid_signature` |
| 3. Anti-replay | `abs(now − ts) > 300 s` | persist raw (`signature_valid=false`) → `401 signature_expired` |
| 4. Persist | insert `webhook_events` row `RECEIVED`, `signature_valid=true` (unique `provider_event_id` — see §5.3) | — |
| 5. Process (§5.3) | dedupe → sanity → confirm → outbox → `PROCESSED` | `200 {"status":"processed"}` / `200 {"status":"duplicate"}` / `200 {"status":"ignored"}` |
| 6. Crash safety | processing exception AFTER step 4 | row stays `RECEIVED` → `500 internal` (canonical writer); replay of `payload_raw` (playbook 10) reproduces the result — the row is the recovery point, no retries |

401 bodies come from the single `ErrorResponseWriter` (codes `invalid_signature`, `signature_expired` —
already in the catalog). Success bodies are the small JSON objects above.

### 5.2 `WebhookSignatureValidator` (pure, TDD first)

- Input: `timestamp` string, raw body bytes, presented signature, secret, `Instant now`.
- Verdict order: timestamp parses → else `INVALID`; `abs(now − ts) ≤ 300 s` → else `EXPIRED`;
  `HMAC-SHA256(secret, UTF-8(ts + "." + rawBody))` lowercase-hex equals presented via
  `MessageDigest.isEqual` → else `INVALID`. Verdicts: `VALID` / `EXPIRED` / `INVALID`.
- **The shared E2 §5.4 vector asserted byte-exact**: `secret=dev-only-secret`, `ts=1787932800`, the exact
  body (with `endToEndId E9040381234567890123456789012345`) → `549eabc4c6f862fdb9322861f43091039de9c75de8107a60945d464755549113`.
  Plus an independent known-answer vector (E2's second-vector pattern). Byte-sensitivity tests: wrong key,
  flipped body byte, `1.0` vs `10` timestamps, non-canonical order.

### 5.3 Processing transaction (one tx; order fixed)

1. Insert `webhook_events`: `provider_event_id = endToEndId + "|" + type` (unique) · `psp_event_id`
   (= the payload's `eventId`, audit only — NOT the dedupe key) · `type` · `txid` · `payload_raw` jsonb ·
   `signature_valid` · `status='RECEIVED'`.
   Unique violation on insert → the event was seen before: re-read the row → `PROCESSED` → `200 duplicate`
   (no-op, zero side effects); `RECEIVED` → reprocess **from `payload_raw`** (§5.1 step 6).
2. Parse `payload_raw`: unknown `type` → status `IGNORED`, `200 ignored` (design §7.2 UNKNOWN policy).
3. Load payment by `txid`: unknown → `IGNORED` + `200 ignored` + WARN (bait audit lives in the row).
4. Sanity: payload `amount != payment.amount` → `IGNORED` + WARN (mismatch is a data-integrity alarm,
   never a confirmation).
5. Confirm: `payment.confirm(endToEndId, FeeBreakdown.of(amount, BpsRate.of(100)), paidAt)` →
   `PaymentRepository.updateIfVersionMatches` (conditional UPDATE; E1 port). Lost race → re-read →
   already `CONFIRMED` → `200 duplicate`.
6. Append outbox row: envelope `payment.confirmed`, `payload {amount, fee, net, late}` (design §7.1;
   `late=false` here — `EXPIRED→CONFIRMED` would force `late=true` and is E5's story), `requestId` null
   (no request id on PSP callbacks).
7. Append `audit_log` row (`command_name=confirm_from_webhook`, `actor_key_id=null`, aggregate=txid).
8. Row → `status='PROCESSED'`, `processed_at=now`. Response `200 processed`.

`payload_raw` is **immutable**: only `status`/`processed_at` ever change. No UPDATE/DELETE grants needed
now; the no-update discipline is enforced in the adapter (E7 formalizes grants).

### 5.4 `V108__webhook_events.sql`

`payments.webhook_events`: `id uuid PK` · `provider_event_id varchar(96) **UNIQUE**` · `psp_event_id varchar(64)` ·
`type varchar(64)` · `txid varchar(25)` · `payload_raw jsonb NOT NULL` · `signature_valid boolean NOT NULL` ·
`status CHECK (IN ('RECEIVED','PROCESSED','IGNORED'))` · `received_at timestamptz` · `processed_at timestamptz`.
Index: (`txid`, `received_at DESC`) for the audit trail. Rows with `signature_valid=false` (rejected attacks)
carry the same shape — they are evidence, never processed.

### 5.5 Late confirmation note (E5 preview, zero work here)

`confirm()` from `EXPIRED` (late=true) is already proven domain behavior. E4 processes whatever arrives;
if a future expiration sets `EXPIRED`, the same code path confirms with `late=true` and the envelope
carries it. Scenario 11 (the race) is E5's.

## 6. Concurrency & races

| Race | Arbitration | Proof |
|---|---|---|
| Duplicate webhook delivered while first still processing | `provider_event_id` unique violation → re-read → `PROCESSED`/`RECEIVED` decision | unit test (store fake throws) + IT (send same webhook twice sequentially AND via 2 threads) |
| Confirm lost race (webhook vs webhook, or vs any confirm path) | conditional `updateIfVersionMatches`; loser re-reads → `duplicate` | unit test (fake returns false) — E1's lesson #12 pattern |
| Replay of `payload_raw` after a crash | row `RECEIVED` → reprocess → same result, `PROCESSED` once | playbook 10 IT (drive the use case twice on the stored row) |

## 7. Testing requirements

- **Pure unit (no Spring):** validator vectors + byte-sensitivity; use case with fakes — every §5.3 branch
  (new/duplicate/ignored×3, lost race, replay) and the tx-order script.
- **MockMvc full-context ITs (house pattern; PG16 Testcontainers; NO WireMock — this epic is inbound-only;
  webhooks are hand-signed per §5.2 with the shared secret):**
  - Scenario 6: invalid signature → `401 invalid_signature` + row persisted `signature_valid=false`;
  - Scenario 7: stale timestamp (clock injected, `-301 s`) → `401 signature_expired`;
  - Scenario 8: same webhook twice → one confirmation, one outbox row, second is `200 duplicate`;
  - Scenario 10: replay — re-drive processing from the stored `payload_raw` → same final state, no new rows;
  - Unknown txid → `200 ignored`; amount mismatch → `200 ignored`; unknown type → `200 ignored`;
  - **Full loop IT:** create via `POST /v1/payments` (E3) → hand-signed `payment.confirmed` → assert
    `CONFIRMED`, `fee=100`, `net=9900`, `end_to_end_id` set, outbox row exact, `webhook_events PROCESSED`;
  - 401 shape via `ErrorResponseWriter` (problem+json, code, no internals).
- Zero sleeps; injected `Clock` everywhere time matters.

## 8. Risks & troubleshooting

| Risk | Likelihood | Mitigation |
|---|---|---|
| Body re-serialization after capture | High (classic) | read raw ONCE into `byte[]`; sign/verify and store THOSE bytes; never `request.getParameter`-style re-parses before capture |
| Charset drift (canonical string) | High (classic) | UTF-8 explicit at every byte conversion; the vector test catches it immediately |
| Clock skew between PSP and us → valid events rejected | Medium | window is ±300 s around server `now` (injected Clock); document that the simulator and API share the host clock in compose |
| `provider_event_id` composite drift (docs say `endToEndId + type`, code invents separator) | Medium | this spec fixes the separator `|` and length 96 — tests assert the composed value |
| Blocking read of the raw body twice | Medium | capture in the controller before anything else; pass bytes down; no `ContentCaching` magic |
| Jackson 3 regression | Medium (lesson #13) | `tools.jackson.*` only; grep gate in S7 |
| **CI red (E3 runs #10/#11) blocks everything** | **Current reality** | Step 0 GATE: diagnose from the uploaded test-reports artifact, fix, `main` green — before any E4 commit |

## 9. Closure checklist (epic DoD)

- [ ] §5.1 pipeline order enforced; §5.2 vector byte-exact (shared + independent); §5.3 tx script proven
- [ ] Scenarios 6, 7, 8, 10 evidenced + full-loop IT green (create → webhook → `CONFIRMED` with fee/net)
- [ ] `V108` applied; `provider_event_id` unique enforced; `payload_raw` never updated after write
- [ ] README honesty callout **full flip** (loop works end to end; no more target-flow warning)
- [ ] design.md §8.2 sync note (endpoint-driven intake interpretation); CHANGELOG; ledger E4 ✅ with the
      **correct green run id**; lessons entry if the red-CI diagnosis taught something durable
- [ ] `mvn -B verify` green locally AND on `main` (CI green is the closure precondition, not a formality)
