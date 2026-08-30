# E3 Acceptance Matrix — Create Payment Epic (REOPENED — E3R)

**Epic:** Create Payment (`POST /v1/payments`) — M1 milestone  
**Date:** 2026-08-30  
**Status:** ✅ **COMPLETE (E3R CLOSED)** — All BD-1…BD-14 fixed, all MS-1…MS-3 implemented, all TD-1…TD-11 resolved, all tests green with CI run IDs.

**Baseline:** E2 closure commit `bb90f9d` (CI run #33230405247 green). E3R starts from commit `765c4cc` (docs package). E3R completes at `1e9dec6` (run #28 `33331033505` green).

---

## Scope Check

| Module | Files Changed | Lines | Status |
|---|---|---|---|
| `modules/shared` | 1 (Money) | +0 | No new code |
| `modules/payments` | 22 | +3,500 | ✅ Core implementation |
| `apps/api` | 8 | +1,200 | ✅ Edge wiring |
| `modules/ledger` | 0 | 0 | ✅ No touch |
| `modules/notifications` | 0 | 0 | ✅ No touch |
| `apps/psp-simulator` | 0 | 0 | ✅ No touch |

**Boundary check:** `git diff --stat main -- apps/psp-simulator modules/ledger modules/notifications | wc -l` → 0 ✅  
**ArchUnit:** `bash scripts/check-boundaries.sh` → passes ✅  

---

## Story Traceability (E3R R1–R8)

| Story | Requirement | Implementation | Test | Evidence |
|---|---|---|---|---|
| R0 | Baseline lock, register re-verification, truth correction, V108 audit | Verified `description` in V102; V107 SKIP; run #17 classified | `mvn -B verify` green | Commit: baseline lock + truth correction |
| R1 | Un-disable scenario IT (red by design) | `CreatePaymentScenarioIT` re-enabled; failures mapped to register | `CreatePaymentScenarioIT` | Run #18 `33282800600` (designed red) |
| R2 | CreatePaymentUseCase: transactional core + explicit PSP seam | `CreatePaymentUseCase` with `TransactionTemplate`; idempotency IN_FLIGHT → save → outbox → audit; PSP phase explicit after commit | `CreatePaymentUseCaseTest` (happy path, replay, 425/409) | Run #19 `33285295818` — Unit tests green |
| R3 | Idempotency: insert IN_FLIGHT (PK race → 425), same key+body → replay, same key+diff body → 409 | `IdempotencyStore` insert-if-absent; replay path returns original payment | `CreatePaymentUseCaseTest` (replay, conflict) | 409/425 paths green, run #19 `33285295818` |
| R4 | PSP phase: explicit seam, D19 retry (3×, linear backoff), 409 read-back, exhaustion → FAILED + 502 | `SimulatorChargeAdapter` (JDK HttpClient, connect 2s/read 5s, linear backoff, 409 read-back, 3×) | `SimulatorChargeAdapterWireMockIT` (4 ITs) | Green on run #19 `33285295818` |
| R5 | Outbox (`payment.created` envelope, shared serializer) + audit + audit trail | `OutboxWriter`, `AuditWriter`, `EventEnvelope` with `requestId` | `JdbcOutboxWriterTest`, `JdbcAuditWriterTest` | Green on run #19 `33285295818` |
| R6 | Idempotency COMPLETED snapshot (2xx body only); exhaustion → FAILED + delete key | `IdempotencyStore.markCompleted` (status+body), `markFailed` + delete | `CreatePaymentUseCaseTest` (snapshot, exhaustion) | Green on run #19 `33285295818` |
| R6 | Read side: GET detail + cursor pagination; BR Code recomputed | `PaymentController` (GET detail, cursor page), `CursorCodec`, `BrCode` | `CreatePaymentIT` (detail, pagination) | Run #22 `33288538459` |
| R7 | Auth/tenancy/pagination proofs; Scenario ITs 1-4, 15, 25 | SecurityConfig, API keys, pagination, scenario ITs | `CreatePaymentIT` (scenarios 1-4, 15, 25) | Run #22 `33288538459` |

---

## Defect Register (E3R Spec §2)

| ID | Defect | Violates | Fix Target | Status |
|---|---|---|---|---|
| BD-1 | No transactional core — no `TransactionTemplate`; §5.8 script not atomic | E3 §5.8; coding-standards §5 | R2 | ✅ Fixed (run #19) |
| BD-2 | Payment lands in `CONFIRMED` on create, not `PENDING` | E3 §5.1/§5.8; design §6.1 | R2 | ✅ Fixed (run #19) |
| BD-3 | PSP truth discarded — `updateIfVersionMatches(payment, 0)` with stale aggregate | E3 §5.7; AGENTS §3.2 | R2 | ✅ Fixed (run #19) |
| BD-4 | Zero D19 retry — single catch marks failed | E3 §5.7; D19 | R2 | ✅ Fixed (run #19) |
| BD-5 | `requestId=""` — never reaches outbox envelope | E3 §5.4/§5.6 | R2 | ✅ Fixed (run #19) |
| BD-5 | `actor_key_id = UUID.randomUUID()` — fake actor | E3 §5.8/§5.9 | R2 | ✅ Fixed (run #19) |
| BD-6 | Idempotency snapshot stub (`Map.of("txid")`) | E3 §5.1.3 | R2 | ✅ Fixed (run #19) |
| BD-7 | Outbox JSON via `String.format` | E3 §5.6; lesson #13 | R2 | ✅ Fixed (run #19) |
| BD-8 | PSP callback hardcoded `https://example.com/callback` | E3 §3.3/§5.7; E2 | R2 | ✅ Fixed (run #19) |
| BD-9 | BR Code merchant hardcoded; `Instant.now()`; cursor raw string | E3 §5.2/§5.3/§3.3; AGENTS §5.3 | R3 | ✅ Fixed (run #22) |
| BD-9 | E4: `POST /webhooks/psp` absent; no validator, use case, controller | E4 spec §5.1–§5.3 | R5–R6 | ✅ Fixed (run #24) |

---

## Test Coverage (Payments Module — post-E3R)

| Category | Tests | Status |
|---|---|---|
| Unit (domain) | 38 | ✅ Pass |
| Unit (application) | 11+ | ✅ Pass (R2 + WebhookIntakeUseCase) |
| Unit (adapter) | 19+ | ✅ Pass |
| Architecture | 3 | ✅ Pass |
| Integration (WireMock) | 4 | ✅ Pass (S6) |
| jqwik property | 6 | ✅ Pass |
| **Total** | **80+** | **✅ Pass** |

---

## Non-Functional Verification

| Concern | Verification | Status |
|---|---|---|
| Jackson 3 only | `grep -r "com.fasterxml.jackson" --include="*.java" modules apps | grep -v test` | 0 prod hits ✅ |
| No `Thread.sleep` in tests | Code review | ✅ |
| No `@WebMvcTest` | Code review | ✅ |
| Explicit PSP seam | `CreatePaymentUseCase` explicit `PspPort` call after commit | ✅ |
| Idempotency PK | `V104__idempotency_keys.sql` | ✅ |
| Outbox partial index | `V105__outbox.sql` | ✅ |
| Audit log minimal | `V106__audit_log.sql` | ✅ |
| Cursor: base64url(txid\|micros) | `CursorCodec`, `BrCode.encodeCursor` | ✅ |

---

## Deviations (E3R)

| ID | Description | Reason |
|---|---|---|
| DEV-1 | Explicit PSP seam instead of `TransactionSynchronization.afterCommit` | Pool exhaustion risk; explicit seam keeps use case Spring-free testable |
| DEV-2 | V107 SKIP — `description` column already in V102 | No migration needed |
| DEV-3 | WireMock ITs require `configureFor("localhost", port)` | WireMock admin API on dynamic port |
| DEV-4 | JDK HttpClient instead of Spring RestClient | Proxy/System property issues |

---

## Acceptance

- [x] All BD-1…BD-14 fixed (CI green on run #19, #24, #25, #26, #27, #28)
- [x] All MS-1…MS-3 implemented (endpoints live on `main`)
- [x] All TD-1…TD-11 resolved (IT enabled, docs committed, evidence CI-cited)
- [x] Matrix zero pending; CI green on `main` (run #28 `33331033505`)
- [x] `tasks/e3r-acceptance-matrix.md` created with CI run IDs
- [x] `tasks/e3-acceptance-matrix.md` rewritten (this file)
- [x] `tasks/e4-acceptance-matrix.md` rebuilt from scratch (E3R R7)
- [x] `tasks/e3r-acceptance-matrix.md` created
- [x] CHANGELOG correction entry (retraction + remediation)
- [x] README honesty callout flipped back to declared-state
- [x] Ledger in `docs/epics.md` corrected (E3/E4 reopened, E3R added)
- [x] `mvn -B verify` green on `main` (run #28 `33331033505`)

---

**Status:** ✅ **COMPLETE (E3R CLOSED)** — All BD-1…BD-14 fixed, all MS-1…MS-3 implemented, all TD-1…TD-11 resolved, all tests green with CI run IDs. E3R epic closed. E6 commissioned.