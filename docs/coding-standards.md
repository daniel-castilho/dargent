# Coding Standards

How code is written in Dargent. Rules here are enforced where possible (ArchUnit, Checkstyle-era conventions,
code review) and expected everywhere. Rationale lives inline — these rules all paid rent somewhere.

---

## 1. Source language & formatting

- 100% English: identifiers, comments, logs, commit messages, test names.
- Standard IntelliJ/Google Java style: 4-space indent, 120-column limit, no wildcard imports.
- Comments explain **why**, never **what**. If you need a "what" comment, the code is wrong.

## 2. Domain modeling (modules: payments, ledger)

- **Rich entities with behavior.** An entity guards its own lifecycle: invalid transitions throw
  (`InvalidTransitionException` → 409), valid ones are impossible to bypass from outside. Zero setters.
- **Factory methods, not builders**, for aggregate creation: `Payment.create(…)` expresses intent and enforces
  invariants at birth.
- **Value objects validate themselves** in construction: `Money`, `Txid` (25 alphanumeric), `EndToEndId`,
  `BpsRate`. A VO in an invalid state must be unrepresentable.
- **Equality by identity** for entities (`id`), by value for VOs.
- **Domain is pure**: no Spring, no JPA, no Jackson, no AWS SDK imports in `domain/` (ArchUnit-enforced).
- **Typed domain exceptions** with HTTP mapping handled centrally:
  `NotFoundException`→404 · `ConflictException`→409 · `ForbiddenException`→403 · `InvalidTransitionException`→409.
- Timestamps are `Instant` (UTC). Durations for TTL/expiry (`expiresIn` as `Duration`).

## 3. Money

- Representation: `Money` value object wrapping `long cents` + ISO currency (`BRL` only in v1).
- Fee arithmetic in basis points using `long` (`amountCents * bps / 10_000`, rounding **down** to the merchant's
  disadvantage on fees, **documented** — and property-tested: `fee(amount,bps) + net == amount` always).
- JSON: `"amount": 10000` (integer cents). Never strings, never decimals.
- SQL: `bigint`. `numeric`/`decimal` columns for money are a review reject.

## 4. Errors & logging

### Errors

- **One canonical error writer** (`ErrorResponseWriter`) emits every error response — global handler,
  authentication entry point, access-denied handler, API-key filter, webhook HMAC filter, rate limiter.
  No filter invents its own body format.
- All business errors are RFC 9457 `application/problem+json` with a machine-readable `code`
  (catalog in design.md §6.3). Clients branch on `code`, never on messages.
- `500` logs method + URI + exception; **never leaks the internal message** to the response.
- `NoResourceFoundException` maps to a canonical 404, never falls through as 500.
- Validation errors include a field→message map.

### Logging

- Boot 4 built-in structured JSON (ECS profile) in prod-like runs; console pattern in dev.
- Every log line carries MDC: `request_id`, `payment_id` (or `aggregate_id`), `merchant_id` where known.
- Log **outcomes and state changes**, not payloads: "webhook accepted (providerEventId=…, type=…)",
  never the raw body (it lives in `webhook_events.payload_raw` — reference the id instead).
- Never log secrets, API keys, HMAC signatures, or full bearer tokens.
- Levels: `ERROR` = needs human attention; `WARN` = degraded but self-healing (fail-open, retry scheduled);
  `INFO` = state transitions; `DEBUG` = development detail. If you log-and-rethrow, you did it wrong — pick one.

## 5. Transactions & persistence

- Transaction boundaries live in the **application layer** (use cases), never in adapters, never in the domain.
- State transitions: conditional `UPDATE` with `version` (optimistic). Zero rows affected ⇒ lost race ⇒ re-read
  and decide. No `SELECT`-then-`UPDATE` without the conditional guard.
- Pessimistic locks (`SELECT … FOR UPDATE`) only where money sums race (refund creation), with minimal scope and
  inside a single transaction that also writes the outbox.
- **Retry on optimistic-lock conflicts belongs outside the transactional seam** — a retry inside the same
  `@Transactional` method replays a poisoned persistence context. Retry in the caller.
- payments module: JPA/Hibernate with **separate domain and persistence models** and explicit mapping in the
  adapter. ledger module: `JdbcClient` only; SQL lives in the adapter, queries named and tested.
- Best-effort side effects (notifications) never fail the primary transaction — bounded retries, then log and move on.

## 6. Migrations (Flyway)

- **Forward-only.** No down/rollback scripts. A bad migration is reverted by a new forward migration or by
  deploying the previous release over a compatible schema.
- **Expand/contract**: add columns/tables in release N; backfill idempotently; remove in release N+1 — never
  rename or destructively migrate in the same release that starts using the change. Blue and green run
  concurrently against one database.
- Every migration touching indexes must state its purpose in a header comment. Partial indexes record their
  predicate in both SQL and data-model-decisions.md.
- `flyway.cleanDisabled=true` outside tests. No manual SQL against shared databases — ever.

## 7. API rules

- Base path `/v1`; money as integer cents; `Idempotency-Key` required on all mutations.
- `merchant_id` only from the API key. Cross-tenant reads → `404`.
- Cursor pagination with an opaque cursor; stable ordering (`created_at DESC, txid DESC`).
- `X-Request-Id` accepted (validated charset/length), generated when absent, echoed in the response, propagated
  to MDC and to outbox events.
- Endpoints appear in the OpenAPI spec via springdoc annotations; errors documented with their `code`s.

## 8. Messaging rules

- Events leave the module **only** through the outbox. The envelope (`eventId`, `type`, `version`,
  `aggregateId`, `merchantId`, `requestId`, `occurredAt`, `payload`) is ours; brokers never leak into it.
- Ordering key = `txid`. Consumers must be idempotent on `eventId` (unique constraint, not "we check first").
- Message handling: process → ack. Poison messages must end in the DLQ with an auditable receive count —
  never silently dropped, never retried forever.
- Any new event type: update the catalog (design.md §7.2), the producer, the consumer mapping, and the
  `UNKNOWN`-type policy (persist raw, mark `IGNORED`, never crash).

## 9. Naming & structure

- Packages by feature inside a module: `domain/model`, `domain/port/in`, `domain/port/out`, `application`,
  `adapter/in/rest`, `adapter/in/messaging`, `adapter/out/persistence`, `adapter/out/psp`, `adapter/out/messaging`.
- Use cases: verb phrases — `CreatePayment`, `ConfirmPaymentFromWebhook`, `RequestRefund`.
- Tests mirror the main tree; ITs end in `*IT` (Failsafe); unit tests end in `*Test`.
- Config classes live in `apps/api` wiring, not inside modules. A module's `@Configuration` is allowed only for
  its own infrastructure adapters, registered via the module's spring factory or import in the app.

## 10. Definition of Done (release gate)

A milestone/tag may be cut only when:

1. All pipeline gates green on the tagged commit (unit, boundaries, SpotBugs, OWASP, IT, coverage, image, smoke).
2. Acceptance matrix for the milestone fully filled with evidence; residual deviations declared in AGENTS.md §8.
3. Release notes written; CHANGELOG updated; lessons.md reviewed — every hard-won lesson of the milestone captured.
4. Docs synced with reality (this file included). Docs that lie are worse than docs that are missing.
