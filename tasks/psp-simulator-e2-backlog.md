# PSP Simulator API E2 — Backlog

## Epic E2 — The Fake Stripe: Charges, Payer Bank, Signed Webhooks & Chaos Knobs

**Priority:** P0
**All stories:** Must
**Companions:** `psp-simulator-e2-spec.md` · `psp-simulator-e2-implementation-sequence.md` · `ai-software-engineer-prompt-psp-simulator-e2.md`

**Execution status:** opened 2026-08-29 after E1 closure (CI run #33225043138 green). Greenfield epic inside
`apps/psp-simulator` — S1 ☑, S2–S8 ☐. TDD mandatory for S2 and S5 (prompt rule 1).
**Decisions:** S1 resolved the spec-vs-M0 config path mismatch by following spec §3.2 — chaos properties
moved to `dargent.psp.chaos.*` (env names `CHAOS_*` unchanged, M0 contract intact).

---

## Epic outcome

`apps/psp-simulator` becomes the honest outside world: a charge API speaking the merchant's txid, a payer
bank with real rules (expiry, double-payment), and an async signed-webhook engine with chaos knobs that let
E3–E5 test retries, dedupe, resurrection and reconciliation deterministically. The API side will meet it in
E4 against the spec's webhook contract — never against shared code.

---

## Story map

```text
FOUNDATION
S0   Baseline lock and contract reading
S1   PSP profile config, Clock, chaos property bindings

COB DOMAIN
S2   Charge model + in-memory store + id generation (tests first)

HTTP API
S3   POST /cobs + GET /cobs/{txid}
S4   POST /cobs/{txid}/payments (payer bank rules)

WEBHOOK ENGINE
S5   HMAC signer (test vector) + async delivery engine

CHAOS
S6   Duplicate / delay / drop / error-rate / latency wiring (deterministic)

PROOFS & CLOSURE
S7   Endpoint ITs + webhook wire IT (stub receiver) + chaos behavior tests
S8   Docs sync, acceptance matrix, ledger, CHANGELOG, lessons
```

---

## S0 — Baseline lock and contract reading ☐

### Work
- [ ] Confirm `main` CI green (E1 closure run is the reference) and simulator module verifies
- [ ] Read spec §5 (contracts) and §6 (chaos) end to end before any test
- [ ] Confirm the M0-seeded env bindings (`dargent.psp.chaos.*`) and keep their names stable

### Acceptance
- [ ] Local `mvn -B -pl apps/psp-simulator -am test` green; no open spec questions

## S1 — PSP profile config, Clock, chaos bindings ☑

### Work
- [x] `PspProfile` config props: `pixKey`, `receiverName`, `receiverCity` (env-overridable; compose-safe defaults)
- [x] `Clock` bean + `WebhookSecret` property (`PSP_WEBHOOK_SECRET`, default `dev-only-secret`)
- [x] `ChaosProperties` bound to the five knobs (names per spec §3.2) + seedable `Random` (`CHAOS_SEED`)
- [x] Config-binding unit test (defaults + override)

### Acceptance
- [x] All knobs resolvable from environment with M0-compatible names; no `Instant.now()` outside the Clock bean

## S2 — Charge model + store + ids ☐ (tests first)

### Work
- [ ] `Charge` (txid, amount, expiresAt, callbackUrl, description?, status, endToEndId?, paidAt?) with
      transition logic: `pay(now)` → rules per spec §5.3; `statusFor(now)` computing EXPIRED
- [ ] `EndToEndIdGenerator` producing `E` + 31 alphanumeric (32 total; SecureRandom)
- [ ] `eventId` generator: `psp-evt-<uuid>`
- [ ] `ChargeStore` (`ConcurrentHashMap<String, Charge>`, `putIfAbsent` for duplicate txid detection)
- [ ] Unit tests first: lifecycle rules, expiry computation across the boundary, generator formats (100-sample
      property), store duplicate rejection

### Acceptance
- [ ] All rules green as pure unit tests — no Spring, no HTTP
- [ ] Generated `endToEndId` matches the API-side `EndToEndId` VO regex `^E[A-Za-z0-9]{31}$`

## S3 — POST /cobs + GET /cobs/{txid} ☐

### Work
- [ ] Create: validate txid `^[A-Z0-9]{25}$`, amount > 0, `expiresAt` in the future, `callbackUrl` http(s)
      → 201 with the charge + PIX profile fields (spec §5.1); duplicate txid → 409 `txid_already_exists`;
      invalid → 400 with `{code, message}`
- [ ] Get: 200 per spec §5.2; unknown → 404 `cob_not_found`; EXPIRED computed for unpaid past expiry
- [ ] Slice tests (MockMvc): happy paths, each validation branch, each error code

### Acceptance
- [ ] Response bodies byte-shape match spec §5.1/§5.2 examples (field names, types)

## S4 — POST /cobs/{txid}/payments (payer bank) ☐

### Work
- [ ] Rules: unknown → 404; expired → 409 `charge_expired`; already paid → 409 `already_paid`; else `pay(now)`:
      status PAID, `endToEndId` set, `paidAt` set, webhook dispatch triggered
- [ ] Response 200 per spec §5.3; slicing tests for every rule branch

### Acceptance
- [ ] All rule branches green; dispatch is triggered exactly once per successful payment (pre-chaos)

## S5 — HMAC signer + async delivery engine ☐ (tests first)

### Work
- [ ] `WebhookSigner`: lowercase-hex HMAC-SHA256 of `timestamp + "." + rawBody` with the configured secret —
      **the spec §5.4 test vector asserted verbatim**
- [ ] `WebhookDispatcher`: builds event JSON per §5.4, signs, delivers via `RestClient` to `callbackUrl`
      with `X-PSP-Timestamp`/`X-PSP-Signature`; async via a bounded executor; single attempt; no retry
- [ ] Unit tests first: signer vector, event JSON shape, executor dispatch to a stub receiver

### Acceptance
- [ ] Signer output for the spec vector is byte-exact; delivery carries exact headers and raw body

## S6 — Chaos wiring (deterministic) ☐

### Work
- [ ] `duplicate`: after a successful payment, the same event (same `eventId`) is delivered twice
- [ ] `delay`: deliveries scheduled after `delayMs` (single shared scheduler; cap 30 000)
- [ ] `dropRate`: seedable-Random discard per delivery; forced modes for tests (`dropRate=1.0` drops all)
- [ ] `errorRate`: endpoint calls may fail 503 `psp_unavailable`; `latencyMs`: sleep before handling (cap 30 000)
- [ ] Behavior tests with forced modes and zero probabilistic assertions; knob semantics documented in class javadoc

### Acceptance
- [ ] Every knob demonstrably changes behavior in a test; defaults keep everything off (M0 contract intact)

## S7 — Integration proofs ☐

### Work
- [ ] Endpoint ITs over the full HTTP surface (`@SpringBootTest` + client): create → get → pay lifecycle with
      a stub receiver capturing webhook deliveries (WireMock **or** a test-local `@RestController` receiver —
      pick one, document)
- [ ] Wire assertions: signature header matches recomputation from raw body + timestamp; event JSON fields
      present; `Content-Type: application/json`
- [ ] Chaos ITs: duplicate (two deliveries, same eventId — Awaitility count 2), drop (zero deliveries),
      delay (delivery observed only after the window)
- [ ] Reactor verify green; image build unaffected

### Acceptance
- [ ] Full lifecycle IT green; the stub receiver's captured webhook validates against the §5.4 vector procedure

## S8 — Docs sync & closure ☐

### Work
- [ ] `tasks/e2-acceptance-matrix.md` filled with evidence (run links, test names)
- [ ] Epics ledger: E2 → ✅; CHANGELOG Unreleased entry; design.md §12 sync if the implementation drifted
- [ ] lessons.md entry if async delivery/chaos determinism taught something non-obvious

### Acceptance
- [ ] Zero `pending` cells; docs truthful; no scope bleed (api app untouched — `git diff main -- apps/api modules` empty)
