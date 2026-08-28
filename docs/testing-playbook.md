# Testing Playbook

Tests are the executable proof of the guarantees in [design.md](design.md) §1.1. A guarantee without a test
here is a claim, and claims about money are worthless. The pyramid tilts toward integration **on purpose**:
our domain is small and the danger lives in the seams (database races, broker delivery, webhook trust).

---

## 1. Prime directive

**Mock only the outside world.** WireMock plays the PSP. Never mock: the database, SNS/SQS, the outbox, the
ledger, our own clock bypass. A test that mocks our own infrastructure proves nothing except that the mock works.

## 2. Taxonomy

| Type | Suffix | Runner | Stack | What belongs here |
|---|---|---|---|---|
| Unit (pure domain) | `*Test` | Surefire (`./mvnw test`) | JUnit 6 + AssertJ | State machine transition tables, `Money`/fee math, BR Code EMV+CRC16, `Txid`/VO validation |
| Slice | `*Test` | Surefire | `@WebMvcTest` etc. | Controller bindings, security chain behavior, error envelope via `ErrorResponseWriter` |
| Integration (IT) | `*IT` | Failsafe (`./mvnw verify`) | Testcontainers: Postgres + LocalStack + WireMock | Every seam: repositories, conditional updates, outbox relay, consumers, reconciliation, HMAC intake |
| End-to-end (E2E) | `*IT` | Failsafe | Real apps via compose (monolith + psp-simulator) | 2–3 happy-path proofs that the wiring assembles and talks |
| Chaos / stress | `*IT` + `@Tag("chaos")` / `@Tag("stress")` | Failsafe, separate CI job | as IT | Webhook drop/duplicate/delay, concurrent refunds, relay kill, DLQ redrive |

Commands:

```bash
./mvnw test                          # fast feedback: unit + slices, no containers
./mvnw verify                        # everything (unit + IT) — the pre-push gate
./mvnw verify -Dskip.unit.tests=true # IT only
./mvnw verify -Dgroups=chaos         # chaos/stress suites
```

## 3. Infrastructure rules

- **Singleton containers** started in a static initializer (never `@Testcontainers`/`@Container` on the shared
  base class — see lessons.md #6), wired with `@ServiceConnection`.
- **WireMock** is the PSP: per-test stubs for latency, timeouts, error rates, and signature payloads; `verify`
  asserts what we asked the PSP.
- **Awaitility** for every eventual outcome. `Thread.sleep` in a test is a defect.
- **`Clock` is an injected bean**: expiration and anti-replay windows are tested by time travel, not by sleeping.
- **Deterministic races:** `ExecutorService` + `CyclicBarrier` pin N threads at the exact contention point.
  Probabilistic stress belongs in `@Tag("stress")`, never in the PR gate.
- **Test names are specifications:**
  `concurrent_refunds_beyond_balance_are_rejected_and_balance_stays_consistent`,
  `webhook_suppressed_reconciler_confirms_payment_alone`.

## 4. Scenario catalog (the executable guarantee list)

Milestone mapping in brackets. Every row = one test (or group) that must exist and stay green.

**Idempotency**
1. Same key + same body → same response; exactly one payment in the database [M1]
2. Same key + different body → `409 idempotency_key_conflict` [M1]
3. Key in flight → `425` + `Retry-After` [M1]
4. Retry after success → snapshot returned, zero new side effects [M1]
5. Cleanup job removes keys older than 24h [M3]

**Webhooks**
6. Invalid HMAC → rejected `401 invalid_signature`, raw payload still persisted [M1]
7. Expired timestamp (> 5 min) → `401 signature_expired` [M3]
8. Duplicate (`endToEndId`+type) → processed once, second is a no-op [M1]
9. Out-of-order/late event → final state consistent, `IGNORED` persisted for unknown types [M3]
10. Replay of `payload_raw` produces the same result as the original [M3]
11. **Expiration and confirmation race → resurrection happens exactly once, with audit trail** [M3]

**Races**
12. Two concurrent refunds of 60% each → one succeeds, one rejected elegantly, `Σ refunds ≤ amount` [M3]
13. N threads on the same conditional UPDATE → exactly one wins, losers re-read and decide [M1/M3]
14. Parallel relay workers with `SKIP LOCKED` → no double publication by workers [M2]
15. Concurrent identical `POST /payments` (same key) → one payment, one 201 + others 425/snapshot [M1]

**Outbox / events**
16. Relay down → events stay `PENDING`; relay returns → publishes [M2]
17. Publish-then-die → duplicate in queue → **consumer dedupes by `eventId`**, one ledger journal [M2]
18. Poison message → DLQ with auditable receive count; no infinite retry [M2]
19. Backoff → `FAILED` → `EXHAUSTED` → audited requeue → `SENT` [M3]
20. Republish tool replays a period without double-journaling [M3]

**Ledger**
21. Every journal closes: `Σ DR = Σ CR` — invariant asserted after each money scenario, plus a daily-proof job test [M2]
22. **Property test (jqwik):** random sequences of payments/partial refunds of varied amounts →
    `balances projection == SUM(lines)` always [M2]
23. Refund beyond available balance → rejected, projection intact [M3]
24. Settlement D+1 moves pending→available exactly once per event [M3]

**PSP chaos (WireMock)**
25. Timeout on cob creation → payment `PENDING`, retries with backoff → `FAILED` only after exhaustion [M1]
26. **Webhook never arrives → reconciler polls the PSP and confirms on its own** [M3 — the signature test, runs in CI]
27. Late webhook beyond anti-replay window → rejected → reconciler confirms [M3]

**Production shape**
28. Lockdown IT: prod profile boots with Swagger/api-docs absent, actuator health-only, `show-details: never` [M4]

## 5. Coverage policy

- Floors **per module** (line + branch), enforced at `verify` on **combined unit + IT data measured after the
  IT step** (unit-only numbers misrepresent reality — lessons.md #8).
- Initial floors: payments 70% line, ledger 75%, shared 80%, notifications 50%, api slices 40% — raised as the
  suite matures, never lowered silently.
- Coverage is a floor, not a goal. A new failure path ships with its own assertion, not with padding.

## 6. Pre-release regression smoke

Executed before cutting a tag (documented in release-runbook §2), with **read-only SQL proofs** after:

1. Compose stack up (blue-green shape, one fleet), migrations applied.
2. Happy path via API: create → pay at simulator → CONFIRMED; ledger journal row exists; outbox drained.
3. Chaos proof: suppress webhook → reconciler confirms.
4. Refund flow: partial refund → projection and journal consistent.
5. SQL proofs (read-only): payment counts by status match expectations; `Σ DR = Σ CR` for the smoke journals;
   no `outbox` rows `PENDING` older than 1 min; DLQ depth 0.

## 7. Flaky policy

- **No permanent flakes.** A test that fails once reruns once locally: green ⇒ investigate timing; red ⇒ triage
  to root cause. Quarantine requires a declared debt entry (AGENTS.md §8) with a target milestone — and a
  quarantined test may not guard a money guarantee.
- Race tests that prove orderings use barriers, not sleeps; if it's flaky, the synchronization is wrong, fix the test.

## 8. What we deliberately do not test

- Framework plumbing (Boot wiring already proven), getters/setters, the notifications module beyond
  "consumed event → acted exactly once", and Swagger UI cosmetics. Each test earns its runtime cost by guarding
  a guarantee; everything else slows the build and rots.
