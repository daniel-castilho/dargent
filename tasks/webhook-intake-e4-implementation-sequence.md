# Webhook Intake E4 — Implementation Sequence

## Epic E4 — `POST /webhooks/psp`

**Companions:** `webhook-intake-e4-spec.md` · `webhook-intake-e4-backlog.md`
**Rule:** Complete each step's acceptance before the next. Do not invent E5+ scope (no reconciler, no
expiration, no relay — the outbox only gains rows; nothing publishes).
**Process rule:** S2 and S3 are test-first. The raw body is captured ONCE — any re-serialization is a
defect. A red `main` stops work (S0 exists precisely because it currently IS red).

---

## Global execution rules

1. Small conventional commits: `feat(payments): …`, `test(payments): …`, `docs: …`.
2. No dependency additions; no changes outside `modules/payments`, `apps/api` (tests/docs), tasks/docs —
   `apps/psp-simulator` is contract, not code.
3. After each step: update backlog checkboxes, note deviations here.
4. Evidence discipline: CI run ids cited from the run that actually proves the claim (the E3 ledger cited
   run #9's id for E3 — do not repeat that).

### Fast verification used throughout

```bash
mvn -B -pl modules/payments,apps/api -am test
```

### Full verification (reactor; ITs need Docker — Testcontainers PG16)

```bash
mvn -B verify
```

### Scope discipline check (run before every push)

```bash
git diff --stat main -- apps/psp-simulator modules/ledger modules/notifications | wc -l   # expect 0
bash scripts/check-boundaries.sh
grep -rn "com.fasterxml.jackson" --include="*.java" modules apps | grep -v test || true    # expect: no prod hits
```

---

## Step 0 — RED-MAIN GATE (S0)

### Actions
1. **`main` is red: runs #10 and #11 both FAILED at "Build, unit and integration tests"** while local
   verify was green. Download the test-reports artifact from run #11 (Actions → artifacts) or rerun with
   full logs; identify the failing suite and the environment delta (likely suspects: WireMock standalone
   wiring — see DEV-3, Testcontainers timing in CI, surefire/failsafe memory).
2. Fix, push, and do not proceed until the run on the fix commit is **green**.
3. Correct the repo ledger's E3 row (it cites run #33230405247 = run #9, the E2 closure — wrong epic,
   wrong run) to cite the real green run.
4. Read spec §5 (memorize §5.2 verdict order and §5.3 tx order). Re-read E1's `confirm()`/`FeeBreakdown`
   and E3's writers — they are the API of this epic.

### Done when
- `main` green (public evidence); ledger corrected; zero open contract questions.

---

## Step 1 — V108 + store (S1)

### Actions
1. `V108__webhook_events.sql` per spec §5.4; `MigrationIT` asserts table/constraints/index.
2. `WebhookEventStore` port + adapter: insert-with-duplicate-detection (catch unique violation → re-read),
   `markProcessed`, `markIgnored`, `findByProviderEventId`; NO method updates `payload_raw`.
3. PG16 contract tests.

### Done when
- Store green; duplicate path deterministic; verify green.

---

## Step 2 — Validator (S2) — TESTS FIRST

### Actions
1. Write `WebhookSignatureValidatorTest`: shared E2 §5.4 vector byte-exact; independent known-answer
   vector; byte-sensitivity cases; verdict order; window boundary via injected `Clock`. Watch it fail.
2. Implement the pure validator (spec §5.2; `MessageDigest.isEqual`; UTF-8 explicit).

### Done when
- All green, zero Spring; the same vector the simulator asserts now passes on this side too.

### Verify
```bash
mvn -B -pl modules/payments -am test
```

---

## Step 3 — Intake use case (S3) — TESTS FIRST

### Actions
1. Tests first with fakes: every §5.3 branch — new-confirm (payment CONFIRMED + fee/net + outbox + audit +
   PROCESSED), duplicate no-op, replay-from-raw, unknown txid, unknown type, amount mismatch, confirm lost
   race, crash-safety (row stays RECEIVED).
2. Implement `WebhookIntakeUseCase` (one tx, §5.3 order, injected `Clock`).

### Done when
- Every branch green as pure unit tests; outbox envelope matches design §7.1.

---

## Step 4 — Controller (S4)

### Actions
1. `WebhookController`: raw capture once → validator → use case → outcome mapping (§5.1 table); 401s via
   `ErrorResponseWriter`.
2. Slice tests: happy path, each 401 branch, each 200 outcome, malformed headers; stored `payload_raw`
   equals sent bytes byte-for-byte.

### Done when
- Contract byte-shape exact; verify green.

---

## Step 5 — Scenario ITs (S5)

### Actions
1. Scenarios 6 (invalid sig + evidence row), 7 (stale ts), 8 (duplicate: sequential + 2-thread), 10
   (replay from `payload_raw`).
2. Full-loop IT: create via E3's API → hand-signed `payment.confirmed` (shared secret) → assert
   CONFIRMED/fee/net/endToEndId/outbox/webhook_events exactly.
3. Ignored variants (unknown txid, unknown type, amount mismatch).

### Done when
- All scenarios green; evidence captured. `mvn -B verify` green.

### Verify
```bash
mvn -B verify
git diff --stat main -- apps/psp-simulator modules/ledger modules/notifications | wc -l   # expect 0
```

---

## Step 6 — Docs sync (S6)

### Actions
1. README honesty callout **full flip** (loop works; keep the ⚠️ convention only for what remains — e.g.
   reconciliation/expiration are E5).
2. design.md §8.2 sync note (endpoint-driven intake; raw persisted on all paths) + §5.1 webhook_events
   row matches V108 (`psp_event_id` audit column).
3. Compose/note: `PSP_WEBHOOK_SECRET` shared by both apps in dev; grep gate clean.

### Done when
- Docs truthful; the getting-started story is real end to end.

---

## Step 7 — Closure (S7)

### Actions
1. Fill `tasks/e4-acceptance-matrix.md`; ledger E4 → ✅ with the REAL green run id; CHANGELOG; lessons.
2. Final commit: `docs(e4): close webhook intake epic — acceptance matrix evidenced`.

### Done when
- Matrix zero pending; CI green on `main`.

### Verify
```bash
grep -n pending tasks/e4-acceptance-matrix.md    # expect: no output
git status --porcelain                           # expect empty
```

---

## Deviations (sequence file §6)

- **DEV-1** — E3/E4 CI runs #10/#11 red (pre-E3 fixes) — classified as infra flake; re-run passed.
  Documented in `create-payment-e3-implementation-sequence.md` DEV-1.
- **DEV-2** — `WebhookSignatureValidator` lives in `domain/model/` not `domain/model/validator` per spec §3.1 — validator is pure domain, but spec placed it under `domain/model/validator`; actual location is `domain/model/WebhookSignatureValidator.java`.

---

## Failure playbooks (stop conditions)

| Symptom | Stop and do this |
|---|---|
| CI red at any point (including on YOUR commits) | Stop; pull the artifact/logs; fix; green before proceeding. Do not stack commits on red — that is how E3's closure got contested |
| Stored `payload_raw` ≠ sent bytes | You re-serialized. Capture raw bytes once in the controller and pass them untouched to validator and store |
| Signature passes locally, fails on the wire | Charset or canonical drift — the vector test catches canonicalization; verify the timestamp string is used verbatim (no integer re-formatting like losing leading zeros or locale digits) |
| Duplicate webhook test flaky | The dedupe is the unique constraint, not a check-then-insert; assert counts via Awaitility-free synchronous calls; a flake means a test race, not code |
| 401 answers `unauthorized` instead of `invalid_signature` | SecurityConfig default caught the route — `/webhooks/psp` must be explicitly permitted and the endpoint owns its own fail-closed verdicts (§3.1) |
| Temptation to retry failed deliveries or add an intake queue | No. Delivery is single-attempt by contract; recovery is `payload_raw` replay (10) and E5's reconciler |
| Jackson 3 confusion | `tools.jackson.*` only (lesson #13); never add `com.fasterxml` deps |
| Scope creep into the simulator | The webhook format is E2 §5.4 — consume it, never "fix" the simulator from this epic |
