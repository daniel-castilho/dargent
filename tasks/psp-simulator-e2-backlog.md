# PSP Simulator API E2 — Backlog

## Epic E2 — The Fake Stripe: Charges, Payer Bank, Signed Webhooks & Chaos Knobs

**Priority:** P0
**All stories:** Must
**Companions:** `psp-simulator-e2-spec.md` · `psp-simulator-e2-implementation-sequence.md` · `ai-software-engineer-prompt-psp-simulator-e2.md`

**Execution status:** opened 2026-08-29 after E1 closure (CI run #33225043138 green). Greenfield epic inside
`apps/psp-simulator` — S1–S7 ☑, S8 ☐. TDD mandatory for S2 and S5 (prompt rule 1).
**Decisions:** S1 resolved the spec-vs-M0 config path mismatch by following spec §3.2 — chaos properties
moved to `dargent.psp.chaos.*` (env names `CHAOS_*` unchanged, M0 contract intact). S3: Boot 4.1.1 ships no
`@WebMvcTest` web slice in `spring-boot-test-autoconfigure` (only json/jdbc slices exist) — slice tests boot
the full app context and use Spring's own `MockMvc` via `webAppContextSetup` (zero new dependencies;
documented deviation). S5: Boot 4.1.1 uses Jackson 3 (`tools.jackson.databind.*`) — `com.fasterxml` packages
do not exist on this classpath; the event records import from `tools.jackson.*`.

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

## S2 — Charge model + store + ids ☑ (tests first)

### Work
- [x] `Charge` (txid, amount, expiresAt, callbackUrl, description?, status, endToEndId?, paidAt?) with
      transition logic: `pay(now)` → rules per spec §5.3; `statusFor(now)` computing EXPIRED
- [x] `EndToEndIdGenerator` producing `E` + 31 alphanumeric (32 total; SecureRandom)
- [x] `eventId` generator: `psp-evt-<uuid>`
- [x] `ChargeStore` (`ConcurrentHashMap<String, Charge>`, `putIfAbsent` for duplicate txid detection)
- [x] Unit tests first: lifecycle rules, expiry computation across the boundary, generator formats (100-sample
      property), store duplicate rejection

### Acceptance
- [x] All rules green as pure unit tests — no Spring, no HTTP
- [x] Generated `endToEndId` matches the API-side `EndToEndId` VO regex `^E[A-Za-z0-9]{31}$`

## S3 — POST /cobs + GET /cobs/{txid} ☑

### Work
- [x] Create: validate txid `^[A-Z0-9]{25}$`, amount > 0, `expiresAt` in the future, `callbackUrl` http(s)
      → 201 with the charge + PIX profile fields (spec §5.1); duplicate txid → 409 `txid_already_exists`;
      invalid → 400 with `{code, message}`
- [x] Get: 200 per spec §5.2; unknown → 404 `cob_not_found`; EXPIRED computed for unpaid past expiry
- [x] Slice tests (MockMvc): happy paths, each validation branch, each error code

### Acceptance
- [x] Response bodies byte-shape match spec §5.1/§5.2 examples (field names, types)

## S4 — POST /cobs/{txid}/payments (payer bank) ☑

### Work
- [x] Rules: unknown → 404; expired → 409 `charge_expired`; already paid → 409 `already_paid`; else `pay(now)`:
      status PAID, `endToEndId` set, `paidAt` set, webhook dispatch triggered
- [x] Response 200 per spec §5.3; slicing tests for every rule branch

### Acceptance
- [x] All rule branches green; dispatch is triggered exactly once per successful payment (pre-chaos)

## S5 — HMAC signer + async delivery engine ☑ (tests first)

### Work
- [x] `WebhookSigner`: lowercase-hex HMAC-SHA256 of `timestamp + "." + rawBody` with the configured secret —
      **the spec §5.4 test vector asserted verbatim** (plus an independent known-answer vector)
- [x] `WebhookEvent.of(charge)` → `{eventId, type, txid, endToEndId, amount, paidAt}` serialized **once** to
      bytes; those bytes are signed and sent (no re-serialization, no pretty-printing)
- [x] `AsyncWebhookDispatcher` (replaces the S4 `NoopWebhookDispatcher`, removed): bounded pool (4 workers,
      daemon threads), `RestClient` connect 2s / read 5s, single attempt, delivery failure → WARN (no retry —
      reconciliation is the E5 story); clock is the injected `Clock` (no `Instant.now()` in serialization path)
- [x] Unit tests first: signer vector (byte-exact), event JSON shape byte-exact, wire IT to a test-local
      `@RestController` receiver (`TestWebhookReceiver`, component-scanned — a duplicate `@Bean` made the
      mapping ambiguous, so no `@TestConfiguration`); a second test asserts single delivery
- [x] Dispatcher failure keeps `dispatch()` async and silent — the controller must never see delivery errors

### Acceptance
- [x] Signer output for the spec vector is byte-exact; delivery carries exact headers and raw body;
      recomputing the signature from captured bytes + timestamp matches the captured `X-PSP-Signature`

## S6 — Chaos wiring (deterministic) ☑

### Work
- [x] `duplicate`: `AsyncWebhookDispatcher.dispatch` enqueues two copies of the SAME event per success
- [x] `delay`: shared single-thread `ScheduledExecutorService`; schedule-based (never a sleep-per-thread); cap 30 000
- [x] `dropRate`: seedable-`Random` discard per delivery; forced `1.0`/`0.0` modes deterministic (no mid-probability assertions)
- [x] `errorRate`/`latencyMs` in `ChaosFilter` (`OncePerRequestFilter`, spec §6 order latency → error → handler);
      503 `psp_unavailable` envelope byte-shape per §5.3; filter is `shouldNotFilter` for anything outside `/cobs/**`
      (actuator health can never be squashed)
- [x] Behavior tests with forced modes: count/absence/timing asserts via Awaitility; latency measured as elapsed
      time on the filter unit tests; knob semantics documented in class javadoc
- [x] Defaults keep every knob off — the M0 compose contract (`all off`) passes untouched

### Acceptance
- [x] Every knob demonstrably changes behavior in a test; defaults keep everything off (M0 contract intact)

## S7 — Integration proofs ☑

### Work
- [x] Test-local `@RestController` receiver chosen and documented (WireMock not used — no new deps; abandoned the
      `spring-boot-webtestclient-testsupport` idea for the same reason). ITs drive real HTTP with plain
      `RestClient` (spring-web), no TestRestTemplate needed (Boot 4.1.1 dragged it out of the test starter)
- [x] `ChargeLifecycleIT`: create → get → pay over the live stack with knobs off; the single delivered webhook
      validates via the §5.4 procedure (recompute over captured bytes+timestamp), event JSON fields + Content-Type asserted
- [x] Chaos ITs driven through the real payment endpoints (not direct dispatcher calls): duplicate → 2 deliveries
      SAME `eventId`; drop-rate 1.0 → zero; delay-ms → quiet before window, one after (endpoint itself still 200)
- [x] Failsafe `*IT.java` runs at `verify` (maven-failsafe integration-test+verify goals in the parent pom) —
      the module `mvn -B -pl apps/psp-simulator -am verify` is green outside Docker too

### Acceptance
- [x] Full lifecycle IT green; the stub receiver's captured webhook validates against the §5.4 vector procedure

## S8 — Docs sync & closure ☑

### Work
- [x] `tasks/e2-acceptance-matrix.md` created with evidence rows (requirement → impl → test → evidence) +
      residuals/deviation table (Jackson 3, no web slice, no WireMock, `.env.example` follow-up → E4)
- [x] Epics ledger: E2 → ✅ in `docs/epics.md` (table row + detail section); CHANGELOG Unreleased E2 entry;
      design.md §12 sync (added `CHAOS_PSP_LATENCY_MS` + `CHAOS_SEED` to the knobs list)
- [x] lessons.md #13: Jackson 3 package relocation + Boot 4 web-test tooling gone (non-obvious, cost the
      S5 compile round-trip); golden rules spelled out
- [x] Final gates: 46 unit/slice + 4 IT green; scope diff empty; boundaries OK

### Acceptance
- [x] Zero `pending` cells; docs truthful; no scope bleed (api app untouched — `git diff main -- apps/api modules` empty)
