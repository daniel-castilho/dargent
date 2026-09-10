# Lessons Learned

Durable register of subtle bugs and decisions that cost debugging time — ours, and lessons seeded from the
reference projects we studied before writing a line of code (marked `[SEED · source]`; inapplicable specifics
were discarded, transferable essence kept).

New lessons go at the **top**, with the next number, date and short context.
Before implementing something similar, re-read the golden rules below.
When a lesson repeats three times, promote it to [coding-standards.md](coding-standards.md).

---

## 21. A seam extracted from a live flow proves itself by what it did NOT touch — the abstraction proof is a diff, not a diagram (2026-09-10, M5 S0/S5 — the extraction story)

M5's contract was "card added **without touching** the PIX domain". The proof that convinced the
owner was not the new code but the **diff audit**: `domain/` zero edits (hard — the two new ports
were additions, not modifications); `application/` exactly 2 files, each with a one-line rationale
(`docs/audit-m5-s0.md`). The PIX suite, floors and ladders ran byte-identical — behavior-equality
as the acceptance bar, not "looks equivalent".

1. Extract the **minimal** seam the second consumer actually needs (`rail()` + `presentment()`),
   not the seam a whiteboard suggests; the card rail then plugs in without renegotiating the core.
2. Keep routing state (which rail a payment rides) in a **port**, out of the aggregate —
   infrastructure state masquerading as domain state is how hexagons rot.
3. The flip citation cites the **diff audit commit**, the seam PR, the card ITs and the gate bite —
   evidence chain, never prose ("same-PR fix; evidence wins" — AGENTS §6).

## 20. CodeQL's log-injection sanitizer must sit AT the sink — upstream charset validation does not flow down (2026-09-09, M5 S2 — the ReplayCache annotations)

GitHub Advanced Security annotated 5 `log.warn(...)` sites in `CachedIdempotencyStore` with
**Log Injection (CWE-117, security-severity 6.1)**: the `Idempotency-Key` header is
client-controlled and the create endpoint validates it for **length only** (8–200 chars, no
charset rule) — so `\n`/`\r` are admissible input, and a forged key like
`idem-a\r2026-09-09 ERROR payment reversed` could forge log lines in the fail-open warn path.

The instinctive fixes are all wrong, and the query model says why (read `LogInjection.qll`
before iterating — Amendment (f)):

- **"Validate the charset at the controller" does not clear the finding.** CodeQL's taint
  analysis is per-method-path, not architectural: a `matches()` guard in `PaymentController`
  is a barrier for flows *through that method*, never for the adapter's own log sinks. Tightening
  the header contract (e.g. `[A-Za-z0-9-]` only, like `X-Request-Id`) would shrink the *risk*
  but not the *finding* — and would be a wire-contract change smuggled inside a security fix.
- **"My keys are UUIDs" is irrelevant** — the header accepts whatever the validation admits,
  and the finding tracks the declared input domain, not the happy path.
- **`replaceAll("\\s", "_")` is NOT a recognized sanitizer.** The model recognizes exactly:
  `String.replace(char, char)` / `replace(CharSequence, …)` cutting `'\n'`/`'\r'` (10/13),
  `replaceAll` with the **literal** patterns `"\n"`, `"\r"`, `"\\n"`, `"\\r"`, `"\\R"` (an
  allow-list `[^…]` form exists but is fragile), or a barrier guard (`contains("\n")`
  branch / `matches()` allow-list) **in the same method as the sink**.

**Fix shipped:** a private `logSafe(String)` at the sink class —
`value.replace('\n', '_').replace('\r', '_')` — applied to every client-controlled argument of
every `log.warn` in the adapter (4 sites; 2 annotations were double-flagged on one site).

**Golden rules:**
- A taint-analysis finding is answered at the SINK with a sanitizer the analyzer recognizes,
  never at the source with an argument about the happy path. Read the `.qll` model first; the
  accepted sanitizer list is short and literal (Amendment (f) applied to security findings).
- Client-controlled values reaching logs (`Idempotency-Key`, request bodies, PSP responses)
  get a local `logSafe()` twin at the logging site — the same discipline as rule 3.7's
  "tenant comes from the credential": defense at the boundary that consumes, not the one that
  produced.
- Length-only validation on a header that later flows into logs is a latent CWE-117; either
  add a charset rule to the wire contract (owner call — it breaks clients) or sanitize at every
  sink. Silent trust in "keys look like UUIDs" is the bug.

## 19. One migration file has exactly one checksum — reverting a modified migration fixes the hypothetical DB and breaks every real one (2026-09-07, E14 S5 — the F1 story)

The two-release migration review (v0.3.0 ↔ v1.0.0) found `V301__create_notifications_schema.sql`
was modified after v0.3.0 without a version bump (E10 S1 `30245af` added the notification table) —
the classic Flyway history violation. The textbook remedy is "revert the file to its v0.3.0 content
and add V302 with the table". The owner approved that remedy. **It was wrong, and the empirical
harness proved it before shipping:**

- **A migration file's identity IS its checksum.** The v0.3.0 V301 (schema-only) and the current
  V301 (schema + table) are different files to Flyway. The database records exactly one checksum per
  version. No single V301 can validate against BOTH histories — the two populations are mutually
  exclusive.
- The v0.3.0 history exists only hypothetically (zero production deployments). The full-content V301
  history is what every real database — dev, CI, drill volumes — carries. **Reverting V301 breaks
  the only databases that exist, with the exact `checksum mismatch` error the fix meant to remove.**
- Resolution: A' — V301 ships unchanged; direct v0.3.0 → v1.0.0 migration declared UNSUPPORTED;
  v0.3.0 data moves via **data-only dump → fresh v1.0.0 boot → load → verify** (a full-dump restore
  is forbidden — it carries the old checksummed history). Proven by rehearsal: seeded v0.3.0 DB →
  data-only dump (history excluded) → fresh 18-migration boot → clean load → identical counts,
  balance proof green, zero history leakage.

**Golden rules:**
- Before reverting a migration, ask "did any database already apply the *current* content?" — if
  yes, the revert is a new checksum-vs-history break, not a repair. Fix by disposition (declare the
  dead upgrade path + add an additive migration for what's missing), never by editing applied content.
- Round-trip a migration remedy against BOTH histories (old-content-applied and new-content-applied)
  with the Flyway version the app embeds, before letting a human decide. The decision needs evidence
  about who the file's population actually is.

## 16. GitHub Actions expressions have no string slicing — `${{ github.sha[0:7] }}` is a parse error that silently suppresses the whole workflow (2026-09-07, E14 S1)

The S1 ci.yml push produced a **0-second, zero-job, logless "failure" run** and PR #6 got no CI
at all. GitHub's workflow parser (stricter than any YAML tool) rejected `${{ github.sha[0:7] }}`
— the expression grammar has no `[start:end]` slicing — and when a workflow file fails
preprocessing, the run entry appears but no jobs are ever scheduled, on ANY event. actionlint
(local binary) reproduced the exact lexer error in seconds: `got unexpected character ':' while
lexing expression`. Fix: slice in shell (`short7="${GITHUB_SHA:0:7}"`), never in the expression.

**Golden rules:**
- A 0s run with zero jobs and no logs = workflow-level parse failure — suspect the expression
  grammar, not YAML validity (PyYAML accepts what GitHub rejects).
- Run actionlint before pushing workflow changes; it mirrors GitHub's parser.
- Prefer shell (`${VAR:0:7}`) over expression syntax for string manipulation inside `run:` blocks.

## 17. `$GITHUB_OUTPUT` heredocs need the delimiter ALONE on its line — a value without a trailing newline glues the terminator (2026-09-07, E14 S2)

The rc1 release failed at "Compose release notes": `printf '%s' "$body" > notes.md` wrote the
body without a trailing newline, so `cat notes.md` + `echo "EOF"` produced `…digest."EOF` on ONE
line. GitHub's file-command parser then reported `Invalid value. Matching delimiter not found
'EOF'` and the step (hence the release) failed — after the image was already pushed. Fix: stop
routing multiline values through `$GITHUB_OUTPUT` entirely; write a file in one step and `cat`
it into a shell variable in the next. Multiline outputs are a footgun; the filesystem is not.

**Golden rules:**
- If a step must emit multiline data for a later step, prefer a workspace file over
  `$GITHUB_OUTPUT` heredocs.
- If you must use the heredoc form, guarantee the content ends with a newline before the
  delimiter line (`printf '%s\n'`, never `printf '%s'`).

## 18. An endpoint that copies without consuming its input window cannot be "just re-run" — tools above it must count the full window (2026-09-07, E14 S3)

`republishSent` (E9) selects `status='SENT' … order by published_at limit 500` and inserts
PENDING copies — the ORIGINALS STAY SENT and nothing marks the window as processed. Re-running
the same >500-row window re-matches the same first 500 rows forever, and a single call reports
`matched=500 republished=500` which looks perfectly healthy. The wrapper script therefore counts
the full window directly and fails (with split instructions) whenever any row would be silently
left behind. General shape: **a bounded tool over non-consuming selection needs an external
completeness check — its own success metric can't see the tail.**

**Golden rules:**
- Before building a "just re-run it" story around a batch tool, verify whether the tool consumes
  its input; if it doesn't, the wrapper must measure the remainder itself.
- A matched==republished report is only meaningful together with a window-size count.

## 15. A test that "hangs" on 100% CPU is usually your own non-terminating loop, not the framework — and a constant prefix can make a "re-generate until different" loop permanent (2026-09-04)

`ProductionLockdownIT` (E11 S3) seemed to hang during context startup — no Spring banner, no container logs, timeouts up to 600–900 s. The suspect was the management port setup (`management.server.port=9090` fixed vs `=0` + `@LocalManagementPort`, RestAssured), and the fix was copied from `spotpobre-api`. Minutes of stabbing at it got nowhere.

The actual culprit was **not** Spring: a `while (prefix(other) == prefix(raw)) other = generate()` fixture loop. `ApiKeyHasher.prefix()` returns the *constant* `psp_test_` (11 chars), so the condition was always true and the loop re-generated keys forever. `jstack <surefire-pid>` on the "hung" JVM showed it instantly: `main RUNNABLE` at `ApiKeyHasher$Base62.encode` (100% CPU) called from the fixture's `setUp()`. One instrumented run with `jstack` replaced every built-in assumption.

**Golden rules:**

1. **A "hang" is a busy loop until proven otherwise** — `jstack` the forked surefire JVM (`jps -l` → `jstack <pid>`) *before* touching frameworks. It turns a 600 s mystery into a 10 s answer.
2. **`ApiKeyHasher.prefix()` is the fixed prefix** — you cannot make two generated keys differ by prefix. The correct way to seat two active keys under the partial unique index `uq_api_keys_key_prefix_active` (one active key per `key_prefix`) is the proven `CreatePaymentIT` order: **revoke the owner key, then insert the second key with the SAME shared prefix**.
3. **Secret-keeping helpers are a minefield**: `configValidator` (prod profile) requires `PSP_BASE_URL` (a real endpoint) and `PSP_WEBHOOK_SECRET` (strong) — dropping them from a prod-profile test fails fast at startup, not in the assertion. The two proven prod tests (`ManagementPortIT`, `JsonLogCorrelationIT`) carry both; copy their full property block, don't "simplify" it.
4. **`management.server.port` is configurable by design** (`DARGENT_MANAGEMENT_PORT`). A matcher that hardcodes 9090 is a real prod bug (custom port ⇒ health checks denied). Inject the configured port; assert the management port on a *different* port than the sibling IT's 9090 to keep contexts independent.

---

The E3R epic began with a repo that claimed "E3 complete: 73 tests pass" (commit `a979c80`) and "E4 complete: full loop proven" (commit `47d2440`). Both claims were false — the `POST /v1/payments` endpoint never existed over HTTP, `CreatePaymentUseCase` violated spec §5.7/§5.8, `CreatePaymentScenarioIT` was `.disabled`, `POST /webhooks/psp` never existed, and the E4 acceptance matrix cited test classes that never existed. CI was green (113 tests) because it ran a suite that exercised nothing the spec required.

The remediation (E3R) did not start by fixing code. It started by **enabling the disabled spec** (`CreatePaymentScenarioIT` was `.disabled`; enabling it produced a red run #18 `33282800600` designed red). That red run was the *first honest signal* — it proved the spec was not implemented and gave a baseline to fix against. Every subsequent fix was traced to a specific register item (BD-1…BD-14) and closed by a test that runs in CI with a cited run id.

**Golden rules:**

1. **Green CI ≠ right tests.** A green CI only proves the current test suite passes. It does *not* prove the test suite exercises the spec. The test suite itself must be proven against the spec (register traceability).
2. **Green CI ≠ right tests ≠ code exists.** The E3R repo had green CI, 113 passing tests, and *zero* of the required endpoints implemented over HTTP. Green CI + missing code = the tests are not the right tests.
3. **The first act of remediation is enabling the disabled spec and watching it fail.** A spec test that cannot compile or is disabled is a stop-and-report defect (AGENTS amendment a). Enabling it produces the first honest signal — red is the color of truth.
4. **Audit beats attestation.** The audit trail (commit chains, run tables, register traceability, run ids) is the only thing that proves completion. A claim without a cited run id is not evidence; it is noise.
5. **Every closure claim must survive an independent API audit.** The E3R handoff was audited via GitHub API (compare commits, runs list, test classnames, file lists). The report was accepted only because the API confirmed: commits exist, runs are green, tests match the register, matrices cite test names + run ids.

**Golden rules:**

1. **Never trust a green CI to mean "done".** Ask: does this test suite exercise the spec? Trace every register item to a test name + run id.
2. **The first act of remediation is enabling the disabled spec.** A red run that maps to the register is worth more than a green run that doesn't.
3. **Every closure claim must cite run ids.** A claim without a run id is not evidence — it is noise.
4. **Audit > attestation.** The audit trail (commits, runs, register traceability) is the only thing that proves completion. A claim without a cited run id is not evidence; it is noise.
5. **Every handoff message must be self-checked against the diff.** Before handoff: `git log -1 --format=%B`, then `git show`, verify every bullet matches a real hunk. A claim the diff doesn't carry = fix the message or the code before handoff (AGENTS amendment e).

---

## 14. Explicit PSP seam beats `TransactionSynchronization.afterCommit` for long-running side effects (2026-08-29)

First design used `TransactionSynchronizationManager.registerSynchronization(afterCommit)` to fire the PSP
call after the core tx. Under load the callback ran *before* the transaction's connection cleanup, so the
PostgreSQL connection stayed bound while the PSP phase held it up to ~20s (3 attempts × (connect 2s + read 5s)
+ backoff). A few slow PSPs hostage the whole pool. Unit tests also became Spring-coupled because
`TransactionSynchronizationManager` dragged spring-tx into the use case.

**Fix:** The `CreatePaymentUseCase` is not `@Transactional`. It delegates the core tx to a private
transactional method (or `TransactionTemplate`), then runs the PSP phase as plain code **after the
transactional method returns**. Same observable behavior (201 still waits on PSP for its real `expiresAt`),
same guarantee ("call PSP only if commit succeeded"), zero pool pressure, zero Spring in the unit test.

**Golden rules:**

1. Side effects that can block I/O (PSP, email, webhook) **never** run inside `afterCommit` — they run in an
   explicit seam **after** the transactional method returns.
2. A Spring-tx-free use case stays fake-testable; `TransactionSynchronization` is a leaky abstraction for
   anything beyond audit logging.
3. Exception semantics: an explicit seam makes "only call the PSP if the commit succeeded" plain control
   flow; `afterCommit` propagates exceptions in non-obvious ways (caller sees rollback, not the original cause).

---

## 13. Boot 4 uses Jackson 3: the package is `tools.jackson.*`, not `com.fasterxml.*` — and the web test client is gone (2026-08-29)

First S5 compile failed with "package `com.fasterxml.jackson.databind` does not exist" while the dependency
tree clearly pulled Jackson in. Not a missing start: Boot 4.1.1 resolves the Jackson 3 line —
`tools.jackson.core:jackson-databind:3.x` (groupId moved from `com.fasterxml.jackson` to `tools.jackson`),
annotations stay at `com.fasterxml.jackson.core:jackson-annotations:2.x`. Imports become
`tools.jackson.databind.ObjectMapper` / `tools.jackson.databind.JsonNode` / `tools.jackson.core.JacksonException`
(no `JsonProcessingException` in Jackson 3). Boot 4 also stripped the classic web-test client tooling: the
`spring-boot-starter-test` no longer drags `TestRestTemplate`/`WebTestClient`, and the only test
autoconfigure slices shipped are json/jdbc — there is no `@WebMvcTest` web slice. `@LocalServerPort`
survives at `org.springframework.boot.test.web.server.LocalServerPort`.

**Golden rules:**

1. Before writing Jackson code on Boot 4, prove the resolved tree (`mvn dependency:tree -Dincludes=tools.jackson,com.fasterxml`) — the groupId relocation is invisible until you compile against it.
2. When a "package not found" references a library you know is transitively present, check for a groupId/artifactId relocation before adding dependencies.
3. ITs that need an HTTP client on Boot 4 use Spring's `RestClient` (`RestClient.builder().baseUrl(...)`) — plain spring-web, zero extra deps. Test doubles that must observe the wire (capture raw body + headers) are test-scope `@RestController`s; a `@Bean` of an already component-scanned `@RestController` double-registers it (Ambiguous mapping) — let component scanning own it.

---

## 12. Catching `OptimisticLockException` from a flush returns `false` but the commit still throws — conditional UPDATE is the only clean lost-race (2026-08-28)

First implementation of `PaymentRepository.updateIfVersionMatches` loaded the row, mutated the managed
`@Version` entity and caught the `flush()` failure to mean "lost race": catch → `em.clear()` → return
`false`. The single-threaded contract suite went green because the stale-version check short-circuits
*before* the flush. The 8-thread race IT exploded on exactly the path the epic exists to prove: Hibernate
marks the transaction rollback-only the moment the flush misses, and the `@Transactional` proxy's commit
throws `UnexpectedRollbackException` — the loser never gets its clean `false`.

**Golden rules:**

1. Lost-race semantics are a **conditional UPDATE** (`SET version = :expected+1 WHERE txid = :txid AND
   version = :expected`; zero rows = lost). Never flush-then-catch for arbitration (AGENTS.md §3.2).
2. A green unit/contract suite on a fake or on single-threaded paths is *necessary but not sufficient* —
   the race IT is what actually crosses the concurrency seam. Write it before trusting the adapter.
3. When Hibernate says "transaction silently rolled back … marked rollback-only", it isn't configuration:
   the persistence context already decided. Redesign the write path, don't tune around it.

---

## 11. WireMock admin port is dynamic — `configureFor("localhost", port)` is mandatory in each test class (2026-08-29)

WireMock's static `stubFor()`, `configureFor()`, etc. target a **static admin port** (default 8080). With
`dynamicPort()`, the admin API moves per test class. Calling `stubFor()` without `configureFor("localhost",
wireMock.port())` registers stubs against port 8080 (which has no WireMock), so all requests hit "connection
refused" even though the WireMock server is up.

**Golden rules:**

1. Every WireMock IT class calls `configureFor("localhost", wireMock.port())` in `@BeforeEach` before any
   `stubFor()`.
3. The `WireMockServer` instance must be a field (not static) so its `port()` reflects the actual port.
4. If stubs appear not to match, check `WireMock.getAllServeEvents()` — it will show requests hitting the
   wrong port (or 404 on the WireMock admin endpoint).

---

The CI boundary script (grep-based second net beside ArchUnit) scanned every `*/domain/*` path under
`modules/` — including test sources. It immediately flagged `BadDomainFixture`, the deliberate Spring
annotation inside a fake domain package whose entire purpose is to prove the ArchUnit gate fires. First CI
run died in 1 second: the gate caught its own canary. The script had never been executed locally before the push.

**Golden rules:**

1. Boundary gates that grep the tree restrict themselves to `*/src/main/java/*`; test scope is governed by
   semantic rules (ArchUnit imports production classes with `DoNotIncludeTests` and fixtures explicitly).
2. A violation-hunting tool must be run against its own repository before shipping — the failure mode "gate
   flags its own proof fixture" is deterministic, not flaky, and costs exactly one CI run to discover.
3. Red CI on step one is a gift: it proves the gate is wired and actually reads the tree. Troubleshoot, don't panic.

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

## 7. Singleton test containers must not use the `@Testcontainers` lifecycle `[SEED · spotpobre-era patterns]`

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