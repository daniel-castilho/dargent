# Lessons Learned

Durable register of subtle bugs and decisions that cost debugging time — ours, and lessons seeded from the
reference projects we studied before writing a line of code (marked `[SEED · source]`; inapplicable specifics
were discarded, transferable essence kept).

New lessons go at the **top**, with the next number, date and short context.
Before implementing something similar, re-read the golden rules below.
When a lesson repeats three times, promote it to [coding-standards.md](coding-standards.md).

---

## 10. Weight `0` does not exist in NGINX upstreams — use `down` `[SEED · spotpobre]`

Setting `weight=0` on an upstream server to drain it crash-looped the load balancer in the reference project.
NGINX simply has no `weight=0`; the supported way to take a peer out of rotation in a blue-green flip is
marking it `down` in the runtime config copy and reloading.

**Golden rules:**

1. Blue-green cutover scripts must only edit a **runtime copy** of the nginx config; the versioned template is never mutated.
2. Toggle fleet membership with `down`/weight changes ≥ 1; verify the flip with `nginx -t` before `nginx -s reload`.
3. Any load-balancer behavior assumed "obvious" gets a smoke test in the deploy script, not faith.

---

## 9. Recreated Docker fleets are invisible to NGINX without runtime DNS re-resolution `[SEED · spotpobre]`

Blue-green means recreating containers. NGINX resolves `upstream` hostnames **once at startup** by default, so
a freshly created `api-green` container keeps answering with the *old* container's IP or fails to resolve at all.

**Golden rules:**

1. Upstreams need `resolver 127.0.0.11 valid=10s` (Docker's embedded DNS) plus `zone` + `resolve` parameters.
2. Pin the NGINX image version and document the interaction — this is exactly the kind of behavior that changes across releases.
3. The deploy exercise (canary → cutover → rollback) is a recorded drill with evidence, not a one-time script.

---

## 8. Measure coverage only after integration tests — unit-only numbers lie `[SEED · spotpobre]`

A pipeline that checked JaCoCo before ITs reported ~54% line coverage on a suite whose real exercise was far
higher, misrepresenting what the tests actually covered. Combined unit+IT exec data is the honest number.

**Golden rules:**

1. `jacoco:check` runs after the IT step, on the merged exec file.
2. Coverage floors are **per module**, so a strong module cannot mask a weak one.
3. Coverage is a floor, not a target: new failure paths bring their own assertions, not padding tests.

---

## 7. Pin the JDK in every CI job that runs the jar `[SEED · spotpobre]`

A runtime smoke launched the production jar with the runner's default JDK and never reached readiness — the
runner's Java 17 cannot load a Java 25 jar. Failures looked like service bugs; they were environment drift.

**Golden rules:**

1. Every job that boots the app pins `setup-java` to the project's JDK version.
2. CI steps that depend on tool versions state them; "whatever is on the runner" is not a version.
3. Readiness waits poll a health endpoint with a bounded loop and dump logs on timeout — never a bare sleep.

---

## 6. Singleton test containers must not use the `@Testcontainers` lifecycle `[SEED · spotpobre-era patterns]`

A singleton container base class combined with `@Container` stops the container after the first test class;
Spring's context cache then holds a dead datasource, producing "flaky" connection failures that are actually a
lifecycle bug.

**Golden rules:**

1. Start shared containers in a static initializer; bypass the per-class extension lifecycle deliberately.
2. `@ServiceConnection` works on manually managed static fields — use it instead of property plumbing.
3. "Flaky" is a diagnosis to make, never an outcome to accept: rerun once, then triage to root cause.

---

## 5. Trivy's SARIF mode ignores severity filters — gate with a second pass `[SEED · spotpobre/flowtxt]`

In SARIF output the scanner ignores severity filtering for both output and exit code (upstream issue), so a
single-pass gate either nags on everything or gates on nothing.

**Golden rules:**

1. Two passes, two purposes: full SARIF as the advisory trail into the Security tab; a table-format pass with
   `exit-code: 1` on fixable HIGH/CRITICAL as the actual gate.
2. Third-party actions are pinned by commit SHA; quirks that cost an afternoon are documented inline in the workflow.
3. Vulnerability scanners are report-first: an unreachable NVD degrades to the cached mirror, it does not break CI.

---

## 4. Emulators have quirks — verify broker assumptions with Testcontainers before building on them `[SEED · spotpobre, adapted]`

The reference project hit a validation quirk in the emulated `BatchWriteItem` and documented the workaround.
Our equivalent risk: LocalStack's SNS→SQS FIFO fan-out (ordering, duplicate delivery, filter semantics) is the
one behavior our whole event path leans on.

**Golden rules:**

1. Write the "broker behaves" proof ITs first in M2 (ordering per `MessageGroupId`, at-least-once duplicates, DLQ redrive).
2. When an emulator deviates, encode the workaround in our adapter and comment with the upstream behavior — never scatter it.
3. Our envelope + idempotent consumers mean broker misbehavior costs us a duplicate, never correctness.

---

## 3. Layer-split Maven modules only work for a single bounded context `[SEED · flowtxt]`

A sibling project splits `api/application/domain/infrastructure` as top-level Maven modules and thrives —
because it has exactly one bounded context. With several contexts, layer-top-level modules couple everything
to everything and make extraction fiction.

**Golden rules:**

1. Module splits follow bounded contexts (`payments`, `ledger`, …); layering lives *inside* a module.
2. The counter-example is part of the design rationale: our route to microservices depends on this choice.
3. Revisit only with evidence, in an ADR, never under deadline pressure.

---

## 2. Optimistic-lock retries must live outside the transactional seam `[SEED · ecommerce]`

Retrying inside the same `@Transactional` method replays a persistence context already marked rollback-only —
the retry re-reads stale data and fails again. The retry belongs in the non-transactional caller, where each
attempt opens a fresh transaction.

**Golden rules:**

1. Lost-race handling (`rows affected == 0`, `ObjectOptimisticLockingFailureException`) is written in the
   caller/use-case orchestration, not deep inside a transactional helper.
2. Best-effort side effects (notifications, merges) never fail the primary flow: bounded retries, then log and reset state.
3. Concurrent ITs prove the retry path; mock-based tests cannot.

---

## 1. Read-modify-write on shared money state is a race, however careful the domain is `[SEED · ecommerce]`

Two concurrent operations both reading `balance == X - 1` and both writing is not a domain-logic problem —
no amount of careful modeling fixes it. Serialization happens at the persistence seam.

**Golden rules:**

1. Refund-style checks use `SELECT … FOR UPDATE` on the anchor row (the payment) with minimal scope, inside the
   same transaction that writes the ledger rows and the outbox event.
2. Or a single atomic statement where semantics allow (`UPDATE … WHERE available >= :amount`).
3. Prove it with a concurrent IT: N threads racing a small balance must yield exactly the correct number of
   successes — that test is the spec.
