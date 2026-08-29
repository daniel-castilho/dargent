# Webhook Intake E4 — Backlog

## Epic E4 — `POST /webhooks/psp`: fail-closed HMAC, anti-replay, dedupe, confirmation

**Priority:** P0
**All stories:** Must
**Companions:** `webhook-intake-e4-spec.md` · `webhook-intake-e4-implementation-sequence.md` · `ai-software-engineer-prompt-webhook-intake-e4.md`

**Execution status:** opened 2026-08-29. **GATE: `main` is red (E3 runs #10/#11 FAILED) — S0 cannot start
until the failure is diagnosed and fixed.** TDD mandatory for S2 and S3 (prompt rule 1). Jackson 3 (lesson #13).

---

## Epic outcome

The money loop closes: the simulator's webhook — signed with the contract E2 craved — is validated
fail-closed against the shared test vector, audited raw no matter what, deduped by
`provider_event_id`, and converted into a real `CONFIRMED` payment with fee breakdown, an outbox row and
an audit trail. Attacks (bad signature, stale timestamp) get `401` + a persisted evidence row. Retries
remain nonexistent by design; replay of `payload_raw` is the recovery story (playbook 10).

---

## Story map

```text
GATE & BASELINE
S0   RED-MAIN GATE + baseline lock + contract reading

PERSISTENCE
S1   V108 webhook_events + WebhookEventStore port/adapter

CRYPTO & DECISIONS (tests first)
S2   WebhookSignatureValidator — shared vector + anti-replay (TDD)
S3   WebhookIntakeUseCase — dedupe/sanity/confirm/outbox (TDD)

HTTP
S4   WebhookController — raw capture, outcome mapping, ErrorResponseWriter 401s

PROOFS & CLOSURE
S5   Scenario ITs: 6, 7, 8, 10 + full-loop create→webhook→CONFIRMED
S6   README full flip + design §8.2 sync + .env note (secret parity)
S7   Matrix, ledger E4 ✅ (correct run id), CHANGELOG, lessons
```

---

## S0 — RED-MAIN GATE + baseline ☐

### Work
- [ ] **Diagnose runs #10/#11** (conclusion FAILURE at "Build, unit and integration tests"): download the
      uploaded test-reports artifact (Actions → run → artifact, owner token) or rerun with logs; identify
      the failing test/cause; fix; push; **run #12 green on `main` required before proceeding**
- [ ] Fix the repo ledger's E3 row (it cites run #33230405247 — that is run #9, the E2 closure; cite the
      real green run)
- [ ] Confirm E3 shapes to build on: `ErrorResponseWriter`, outbox writer, audit writer, `PaymentRepository`
- [ ] Read spec §5 end to end (§5.2 vector, §5.3 tx script) before any test

### Acceptance
- [ ] `main` green (public run evidence); no open spec questions

## S1 — V108 + store ☐

### Work
- [ ] `V108__webhook_events.sql` per spec §5.4 (unique `provider_event_id`; status CHECK; index)
- [ ] `MigrationIT`: table + constraints exist
- [ ] `WebhookEventStore` port + adapter: insert-catch-duplicate-re-read, `markProcessed`, `markIgnored`,
      `findByProviderEventId`, raw-immutability (no update path for `payload_raw` in the adapter at all)
- [ ] Contract-style tests against real PG16

### Acceptance
- [ ] Store green on PG16; duplicate insert deterministically detectable; raw cannot be mutated

## S2 — WebhookSignatureValidator ☐ (tests first)

### Work
- [ ] Tests first: **shared E2 §5.4 vector byte-exact** (`549eabc4…9113`), independent known-answer vector,
      byte-sensitivity (wrong key, flipped body byte, `1.0` vs `10`), verdict order (parse → window → MAC),
      window boundary (±300 s exactly → VALID; 301 s → EXPIRED) via injected `Clock`
- [ ] Implement pure `WebhookSignatureValidator` (`MessageDigest.isEqual`; UTF-8 everywhere)

### Acceptance
- [ ] All verdict tests green, zero Spring; the shared vector asserts byte-exact

## S3 — WebhookIntakeUseCase ☐ (tests first)

### Work
- [ ] Tests first (fakes): §5.3 script row by row — new event confirm path (payment + fee/net + outbox +
      audit + PROCESSED), duplicate (`PROCESSED` re-read → no-op), replay (`RECEIVED` → reprocess from
      `payload_raw` → same result), unknown txid → IGNORED, unknown type → IGNORED, amount mismatch → IGNORED,
      confirm lost race → re-read → duplicate, crash-safety (store throws mid-tx → row stays RECEIVED)
- [ ] Implement the use case: one transaction, order per §5.3, injected `Clock`

### Acceptance
- [ ] Every §5.3 branch green as pure unit tests; no HTTP types

## S4 — WebhookController ☐

### Work
- [ ] Capture raw bytes ONCE; extract the two headers; call validator → use case; map verdicts/outcomes to
      §5.1 responses (`401`s via `ErrorResponseWriter`; 200 bodies `{"status": …}`)
- [ ] Slice tests (full-context MockMvc): happy path, each 401 branch, each 200 outcome, malformed headers
      (missing/garbage timestamp, non-hex signature)

### Acceptance
- [ ] Contract byte-shape per §5.1; raw bytes reach the use case unmodified (test asserts stored payload_raw
      equals sent bytes)

## S5 — Scenario ITs ☐

### Work
- [ ] Scenario 6: invalid signature → 401 + evidence row (`signature_valid=false`)
- [ ] Scenario 7: stale timestamp → 401 `signature_expired`
- [ ] Scenario 8: duplicate (sequential AND concurrent 2-thread) → one confirmation, one outbox row, 200 duplicate
- [ ] Scenario 10: replay from `payload_raw` → same result, no new rows
- [ ] Full loop: create (E3 API) → hand-signed `payment.confirmed` → `CONFIRMED`, fee=100/net=9900,
      `end_to_end_id` set, outbox envelope exact, `webhook_events PROCESSED`
- [ ] Unknown txid / unknown type / amount mismatch → 200 ignored variants

### Acceptance
- [ ] All scenarios green with evidence captured for the matrix

## S6 — Docs sync ☐

### Work
- [ ] README: honesty callout **full flip** — the loop works end to end (create → pay → webhook → CONFIRMED);
      remove the target-flow warning, keep the ⚠️ convention for anything still pending (reconciliation notes)
- [ ] design.md §8.2: sync note — intake is endpoint-driven (fail-closed proven by tests), raw persisted on
      all paths; §5.1 `webhook_events` row matches V108 (add `psp_event_id` audit column note)
- [ ] Compose note: `PSP_WEBHOOK_SECRET` shared by api + simulator (same env value in dev)
- [ ] Grep gate: no `com.fasterxml.jackson` in prod sources

### Acceptance
- [ ] Docs truthful; the README curl + pay + webhook story is REAL

## S7 — Closure ☐

### Work
- [ ] `tasks/e4-acceptance-matrix.md` filled (scenario → implementation → test → evidence)
- [ ] Ledger E4 → ✅ citing the **actual green run id**; CHANGELOG; lessons entry (candidates: the red-CI
      diagnosis, raw-capture-once pattern, composite dedupe key)
- [ ] Final commit: `docs(e4): close webhook intake epic — acceptance matrix evidenced`

### Acceptance
- [ ] Matrix zero pending; CI green on `main`; scope clean (`apps/psp-simulator` untouched)
