# AGENTS.md — Rules for AI Agents and Human Contributors

This repository is engineered by humans and AI agents together. These rules are **binding for both**.
They exist because every one of them guards a money, race or boundary guarantee. When a rule blocks you,
do not work around it silently — raise it, get the rule changed here, then proceed.

**Project in one paragraph:** Dargent is a PIX payment processing backend (modular monolith, Java 25, Spring Boot 4.1,
PostgreSQL 16, SNS/SQS FIFO via LocalStack, NGINX blue-green on bare metal). The authoritative design lives in
[`docs/design.md`](docs/design.md). Read it before non-trivial work. The scenario catalog in
[`docs/testing-playbook.md`](docs/testing-playbook.md) is the executable definition of "correct".

---

## 1. Language

- **All sources are 100% English**: identifiers, comments, logs, commit messages, documentation, test names.
- Conversations with stakeholders may be in Portuguese; everything committed is English.

## 2. Module boundaries (verified by ArchUnit + `scripts/check-boundaries.sh` in CI)

| Module | May depend on | Must never |
|---|---|---|
| `modules/payments` | `modules/shared` only | Import ledger/notifications classes; call ledger synchronously |
| `modules/ledger` | `modules/shared` only | Import payments classes (it consumes events, period) |
| `modules/notifications` | `modules/shared` only | Contain business rules |
| `modules/shared` | nothing | Grow into a junk drawer (see rule 2.1) |
| `apps/api` | all modules (wiring only) | Contain domain logic |
| `apps/psp-simulator` | nothing shared | Share code with the API (it is the *outside world*) |

2.1. `shared` may only contain: `Money`, the event envelope, the canonical error contract, JSON serialization
support. A class goes to `shared` only if **two or more modules** need it and it contains **zero business rules**.

2.2. Inside a module, the hexagonal layout is fixed:
`domain/` (model + port/in + port/out) · `application/` (use cases) · `adapter/in/` · `adapter/out/`.
`domain` imports no Spring, no JPA, no AWS, no Jackson.

2.3. Cross-module communication is **events only** (outbox → SNS → SQS). Synchronous calls between business
modules are forbidden. Spring `ApplicationEvent` is **not** the bus.

2.4. Database: schema-per-module. **No foreign keys and no JOINs across schemas.** A migration touching schema
`payments` lives in the payments module's Flyway location.

## 3. Non-negotiable invariants

3.1. **Money is `Money`**: `cents: long` + currency. Never `double`/`float`/`BigDecimal` in money paths,
never in Java, never in JSON (cents as integer), never in SQL (bigint). Fees in basis points.

3.2. **The database arbitrates races.** Every state transition is a conditional `UPDATE … WHERE status IN (…)` —
zero rows affected means you lost the race: re-read and decide. Refund creation takes `SELECT … FOR UPDATE`
on the payment row with minimal scope. Unit tests cannot prove this; concurrent ITs must.

3.3. **Everything is idempotent.** API mutations take `Idempotency-Key`; webhook dedupe is
`provider_event_id` unique; consumers dedupe on `eventId` unique. At-least-once delivery is assumed everywhere.

3.4. **The outbox is the only exit for events.** No publishing inside request transactions. The relay owns
publication; failures follow the backoff → `FAILED` → `EXHAUSTED` → requeue lifecycle.

3.5. **The ledger is append-only.** No `UPDATE`, no `DELETE` on `journal_entries`/`ledger_entries` — not in code,
not in DB grants. Corrections are reversing entries. `Σ DR = Σ CR` per journal, always.

3.6. **Webhook payloads never become domain entities directly.** Translation happens at the boundary
(anti-corruption layer in the adapter).

3.7. **The tenant comes from the credential.** `merchant_id` is never taken from path, query or body.
Cross-merchant access answers `404`, not `403`.

3.8. **Forward-only migrations.** No rollback scripts. Every migration must keep release N+1 running against
release N's schema (expand/contract). Blue and green share the database.

3.9. **Mocks only mock the outside world.** Never the database, the queue, the outbox, or the ledger.

## 4. Security rules

4.1. `SecurityConfig` is the **single source of truth** for route authorization. A new endpoint without an
explicit rule is a defect — treat it as a build breaker.

4.2. Secrets come from the environment only. The boot fails fast (aggregated report) on: unresolved placeholders,
short secrets, static AWS credentials in prod profile. Never commit `.env` files or keys.

4.3. API keys are stored as SHA-256 hashes with an indexed prefix; comparison is constant-time.

4.4. Webhook intake is fail-closed: HMAC-SHA256 over `timestamp + "." + rawBody`, anti-replay window of 5 minutes,
raw payload persisted even on invalid signatures (attack audit).

4.5. Production exposure is proven by test: Swagger/api-docs absent, actuator health-only, `show-details: never`.

## 5. Testing rules

5.1. Test names read as specifications: `concurrent_refunds_beyond_balance_are_rejected_and_balance_stays_consistent`.

5.2. New money/race guarantee ⇒ new test in the catalog before merge (see playbook §5).

5.3. Eventual outcomes use Awaitility. Time travel uses the injected `Clock`. `Thread.sleep` in a test is a defect.

5.4. Coverage floors are per-module (combined unit + IT data measured after ITs). Dropping below floor breaks the build —
fix or write tests, do not lower the floor silently.

## 6. Definition of Done (any change)

- [ ] Tests follow the taxonomy (playbook §2); money/race guarantees covered
- [ ] `./mvnw verify` green locally (unit + IT)
- [ ] ArchUnit + boundary script green; no new cross-schema access
- [ ] Docs synced: design.md (if behavior changed), relevant playbook/standards sections, CHANGELOG Unreleased
- [ ] Acceptance matrix row updated (requirement → implementation → test → evidence) when closing a milestone item
- [ ] Logs/metrics added for any new failure path (see observability.md)
- [ ] No `shared` growth without meeting rule 2.1

## 7. Commits, branches, PRs

- Conventional commits: `feat(payments): …`, `fix(ledger): …`, `docs: …`, `test: …`, `chore: …`, `refactor: …`
- Branches: `feat/<slug>`, `fix/<slug>`, `chore/<slug>`
- PRs: small, one concern, description states which guarantee(s) it touches; CI must be green (no skipped gates)

## 8. Known technical debt

Ledger of honestly declared debt. Keep it short by paying debt within the milestone, or declare it here
with an owner and a target milestone.

| ID | Debt | Owner | Target |
|---|---|---|---|
| DEBT-1 | `Payment.restore()` (persistence hydration) trusts snapshots without revalidating invariants — correct for ORM hydration, but a lying adapter could materialize an invalid aggregate. Add a rejecting-contract test (corrupt snapshot → exception) or adapter-side validation when the persistence seam is next touched. | — | E5 (paid — `PaymentTest.restore_rejects_*`, `242b6e3`/`33693408878`) |
| DEBT-2 | Dev-default DB credentials (`dargent`/`dargent`) present in `application.yaml`/compose defaults. Acceptable while `ConfigValidator` (which will refuse defaults in prod profile) does not exist — blocked on DEBT target of design.md §8.3. | — | E3/M4 |
| DEBT-3 | E3/E4 closed on paper; paid by E3R. Rules prevent recurrence: (a) spec-test that cannot compile = stop-and-report; (b) inbound HTTP adapters live in boot app; (c) grep/verification output cites commit id. | — | E3R (paid) |
| DEBT-4 | Money state can dangle: a payment confirmed yet never journaled (or a POSTED journal event with no confirmed payment) was previously undetectable. Add a coverage auditor. | — | E5 (paid — `JournalCoverageAuditor` + `JournalCoverageAuditorIT`, `470e10c`/`33711320405`) |

## 9. Governance amendments (E3R earned)

**Amendment (a):** A spec-test that cannot compile is a stop-and-report defect; replacement/deletion requires owner sign-off (DEV-R2-4).

**Amendment (b):** Inbound HTTP adapters live in the boot app; AGENTS §2.2 language reconciled with the as-built convention (DEV-R6).

**Amendment (c):** Any pasted grep/verification output cites the commit id it ran at; "done" = pushed + green run id (TD-10).

**Amendment (d):** A schema↔spec divergence (migration vs contract) is a stop-and-report owner decision — never resolved in-block, however defensible the improvisation (born BD-14).

**Amendment (e):** Pre-push message self-check: every message bullet re-verified against the diff (born TD-11 — three instances: TD-5, TD-10, TD-11).

## 10. Release history conventions

Releases are annotated tags `vX.Y.Z` cut when a milestone meets its DoD (coding-standards §10).
Release notes go to `docs/releases/vX.Y.Z.md` + CHANGELOG. Version in the POM stays `1.0-SNAPSHOT` during
development; release naming comes from git tags only.
