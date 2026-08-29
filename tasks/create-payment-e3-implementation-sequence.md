# Create Payment E3 — Implementation Sequence

## Epic E3 — First Command: `POST /v1/payments`

**Companions:** `create-payment-e3-spec.md` · `create-payment-e3-backlog.md`
**Rule:** Complete each step's acceptance and verification before starting the next. Do not invent E4+ scope
(no webhook intake, no relay — the outbox only gains rows here, it is never read for delivery).
**Process rule:** S3 and S5 are test-first (red → green → refactor). Backoff sleeps are injected and recorded —
a real `Thread.sleep` in any test is a defect. All JSON is Jackson 3 (`tools.jackson.*` — lesson #13).

## Deviations (rule 5)

- **DEV-1 — PSP phase runs on an explicit seam, not `TransactionSynchronization.afterCommit` (approved decision 3).**
  Proposal rejected on architectural grounds: pool exhaustion under load (callbacks run before tx cleanup, so the
  Postgres connection stays bound while the PSP phase holds it up to ~20s; a few slow PSPs hostage the whole pool),
  unit-testability (a Spring-tx-free `CreatePaymentUseCase` stays fake-testable), and exception semantics (an explicit
  seam makes "only call the PSP if the commit succeeded" plain control flow). The approved shape:
  `CreatePaymentUseCase.execute()` is **not** `@Transactional`; it calls the transactional core (internal transactional
  component or `TransactionTemplate`), receives the result, and runs the PSP phase as ordinary code **after the
  transactional method has returned**. Same observable behavior (201 still waits on the PSP for its real `expiresAt`),
  same guarantee ("call PSP only if commit succeeded"). Candidate lesson for lessons.md.

---

## Global execution rules

1. Small reviewable vertical commits, story-sized: `feat(payments): …`, `feat(api): …`, `test(payments): …`.
2. Read the story acceptance before coding; tests ship with the change.
3. No dependency beyond spec §3.4 (WireMock standalone, test scope, payments only) without explicit approval.
4. A red `main` baseline stops work; regressions diagnosed before new commits.
5. After each step: update backlog checkboxes, note deviations here.
6. The epic touches ONLY `modules/payments`, `modules/shared`, `apps/api`, its tests, tasks/docs — plus
   env-only additions to compose (`.env.example`, api service environment). Nothing else.

### Fast verification used throughout

```bash
mvn -B -pl modules/payments,modules/shared,apps/api -am test
```

### Full verification (reactor; payments ITs need Docker — Testcontainers PG16 + WireMock)

```bash
mvn -B verify
```

### Scope discipline check (run before every push)

```bash
git diff --stat main -- apps/psp-simulator modules/ledger modules/notifications | wc -l   # expect 0
bash scripts/check-boundaries.sh                                                          # domain purity net
grep -rn "com.fasterxml.jackson" --include="*.java" modules apps | grep -v test || true    # expect: no prod hits
```

---

## Step 0 — Baseline lock (S0)

### Actions
1. Confirm CI green; local reactor verify green (Docker up for payments ITs).
2. **Inspect `modules/payments/src/main/resources/db/migration/V102__*.sql`** — `description` column?
   Record the V107 run/skip decision in the backlog.
3. Read spec §5 (contracts — memorize §5.1.3 and §5.8), §6, §7. Re-read E1's `Payment`/ports/exceptions
   signatures (they are the API of this epic).

### Done when
- Verify green; no open contract questions (ask, don't guess); V107 decision recorded.

---

## Step 1 — Error contract + request id (S1)

### Actions
1. `ErrorResponseWriter` + catalog constants (including `psp_unavailable`/502); global advice mappings;
   `NoResourceFoundException` → canonical 404; 500 shape without internals.
2. `RequestIdFilter` (validate `[A-Za-z0-9-]{8,64}` / generate / echo / MDC).
3. Slice tests against a throwaway secured context (auth arrives in Step 2 — use `permitAll` interim config
   and keep it behind the final `SecurityConfig` shape).

### Done when
- Every error the app can emit goes through the single writer; request-id echoed; verify green.

### Verify
```bash
mvn -B -pl apps/api,modules/payments -am test
```

---

## Step 2 — API keys + SecurityConfig + ConfigValidator (S2)

### Actions
1. `V103__api_keys.sql`; `ApiKeyHasher` (SHA-256 hex lowercase, constant-time compare, 16-char prefix);
   `ApiKeyAuthenticationFilter` (Bearer → `ApiKeyPrincipal`, 401 via the writer).
2. Final `SecurityConfig`: `/v1/**` authenticated · `/webhooks/psp` permitAll · actuator health/info permitAll.
3. Dev-key provisioner (`dev` profile + `DARGENT_DEV_API_KEY`); minimal `ConfigValidator` (spec §3.5),
   aggregated fail-fast report.
4. Tests: hashing, filter, revoked, provisioning idempotency, validator aggregation.

### Done when
- 401 problem+json unauthenticated; principal carries merchant; no raw key stored or logged; verify green.

---

## Step 3 — BR Code composer (S3) — TESTS FIRST

### Actions
1. Write `BrCodeTest`: golden vector (spec §5.5, 174 chars, CRC `EDD2`) byte-exact; CRC self-check property
   (recomputed CRC over the payload prefix equals the trailer); sanitization rejects (charset, lengths,
   txid shape); amount formatting. Watch it fail.
2. Implement `BrCode.of(...)` — TLV builder + CRC16-CCITT-FALSE, zero dependencies, zero Spring.

### Done when
- Golden vector byte-exact; property green; composer used by nothing yet.

### Verify
```bash
mvn -B -pl modules/payments -am test
```

---

## Step 4 — Migrations + stores (S4)

### Actions
1. `V104`–`V107` per spec §3.2 (V107 per the Step 0 decision); `MigrationIT` asserts tables/constraints/index.
2. Ports + adapters: `IdempotencyStore`, `OutboxWriter`, `AuditWriter`, `PaymentQueryPort` — fake + JPA
   contract-suite style (E1 pattern) where a suite makes sense; `MigrationIT` for the DDL truth.
3. Shared: `EventEnvelope` record + Jackson-3 serializer (`tools.jackson`, serialized once to jsonb bytes).

### Done when
- Stores green on PG16; partial outbox index present; envelope serializes deterministically (key order test).

---

## Step 5 — CreatePaymentUseCase (S5) — TESTS FIRST

### Actions
1. Unit tests first (fakes for every port): §5.1.3 table row by row; §5.8 core script order (idempotency →
   payment → outbox → audit, one tx); snapshot-2xx-only (PSP failure path deletes the key row);
   txid collision bounded retry; `markFailed` lost-race re-read; D19 exhaustion path (use-case level,
   PSP port faked).
2. Implement `CreatePaymentUseCase` (transactional core + PSP phase orchestration; injected `Clock`,
   injected sleeper for backoff).

### Done when
- Every §5.1.3 row green as pure unit tests; zero `Thread.sleep`; envelope written with `PaymentCreated`.

### Verify
```bash
mvn -B -pl modules/payments -am test
```

---

## Step 6 — PSP adapter (S6)

### Actions
1. `PspPort` port + `SimulatorChargeAdapter` (RestClient, timeouts 2 s/5 s); retry policy via recorded sleeper;
   409 `txid_already_exists` → read-back path.
2. WireMock ITs (dynamic port): request body = E2 §5.1 byte-shape; success → `expires_at` updated (PSP truth);
   exhaustion → exactly 3 requests, backoff values recorded, `FAILED` + `PaymentFailed` outbox row + 502
   `psp_unavailable`, idempotency key row deleted.

### Done when
- Adapter contract proven against the wire; D19 fully green; `mvn -B verify` still green.

---

## Step 7 — Reads (S7)

### Actions
1. `GET /v1/payments/{txid}`: detail shape §5.2 (BR Code recomputed; `expiresIn` recomputed); cross-tenant 404.
2. `GET /v1/payments`: keyset page `(created_at DESC, txid DESC)`, clamp 100, `CursorCodec` (unit-tested),
   `nextCursor` null on last page.
3. Slice tests: shapes, 404 paths, pagination walk over 25 seeded rows (fixed clock), stability under insertion.

### Done when
- Detail/list byte-shape exact; cursor math proven; verify green.

---

## Step 8 — Scenario ITs (S8)

### Actions
1. Playbook 1, 2, 3, 4 over HTTP (full context + PG16 + WireMock): replay byte-equal; conflict; 425 + header;
   snapshot zero side effects.
2. Playbook 15: 4 threads + `CyclicBarrier`, same key/body → exactly one 201, others 425, exactly one row.
3. Playbook 25: WireMock always-500 → 3 recorded attempts, backoff values asserted from the recorded sleeper,
   `FAILED` + 502 contract + `PaymentFailed` outbox row + key row deleted.
4. Auth/tenancy proofs: no key → 401; other tenant → 404; revoked → 401.

### Done when
- All scenarios green; evidence (test names + local run refs) captured for the matrix.

### Verify
```bash
mvn -B verify
git diff --stat main -- apps/psp-simulator modules/ledger modules/notifications | wc -l   # expect 0
```

---

## Step 9 — Closure (S9)

### Actions
1. Fill `tasks/e3-acceptance-matrix.md` (requirement → implementation → test → evidence).
2. Docs sync: design §6.3 `psp_unavailable`; §5.1 `description` row if V107 ran; README honesty callout
   (create works; webhook step E4); `.env.example` (`PSP_*`, `DARGENT_*`, E2 follow-up `CHAOS_*`).
3. Lesson #13 grep gate clean; ledger E3 → ✅; CHANGELOG; lessons entry if warranted.
4. Final commit: `docs(e3): close create payment epic — acceptance matrix evidenced`.

### Done when
- Matrix zero pending; CI green on `main`; the README curl answers `201` against a local compose stack.

### Verify
```bash
grep -n pending tasks/e3-acceptance-matrix.md    # expect: no output
git status --porcelain                           # expect empty
```

---

## Failure playbooks (stop conditions)

| Symptom | Stop and do this |
|---|---|
| `ClassNotFoundException` / missing `com.fasterxml.*` | Wrong Jackson. Boot 4.1 = Jackson 3: import `tools.jackson.databind` (lesson #13). Never add `com.fasterxml` deps to "fix" it |
| Slice test can't find `@WebMvcTest` | It does not exist in Boot 4.1. Full-context MockMvc (`webAppContextSetup`) is the house pattern (E2 deviation). Stop trying slices; boot the context |
| Auth returns 403 where 401/404 was expected | `SecurityConfig` default rule caught it. A new endpoint without an explicit rule is a defect (AGENTS §4.1). Fix the rule; cross-tenant is a **404 from the query**, never a 403 from security |
| 425/snapshot IT flaky | Barrier before requests; assert counts not identities; Awaitility nowhere here — the flow is synchronous; a flake means a race in the test, not the code |
| WireMock port collision in CI | Dynamic ports only (`wiremock().options(dynamicPort())`); never a fixed port |
| Backoff test takes seconds | You slept for real. The sleeper is injected and recorded — assert the recorded values, never wait them |
| PSP adapter retries a 409 | 409 `txid_already_exists` is the already-created success path (read-back), never a retry (spec §5.7) |
| Envelope JSON key order varies between runs | Serialize once through the shared Jackson-3 serializer with a fixed order test; never re-serialize the map ad hoc |
| Scope creep into psp-simulator/ledger/notifications | Revert; the scope check before every push is zero lines or the push does not happen |
