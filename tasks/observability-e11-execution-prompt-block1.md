# E11 — Execution Prompt, Block 1 (S0–S3)

Engineer brief. Contracts: `observability-e11-spec.md` (this package) + `docs/design.md` §9 +
`docs/observability.md` + `docs/handoff-dod.md` (in-repo, contractual). Sequence + stop conditions:
`observability-e11-sequence.md`. Deliver S0→S3, then STOP and report (report state + gaps — never
closure claims). Zero migrations expected. Exactly one new env: `DARGENT_MANAGEMENT_PORT` (default
9090) — any additional env/name = STOP, owner decides (§4.1 is contract).

## S0 — Foundation (config + build files; proof IT lands in the same commit)

1. `apps/api/pom.xml`: add `micrometer-registry-prometheus` (Boot-managed version — no explicit
   version tag).
2. `application.yaml`: `management.server.port: ${DARGENT_MANAGEMENT_PORT:9090}`; exposure
   `health,info,prometheus`. `SecurityConfig`: REMOVE the `/actuator/**` main-port matchers —
   `denyAll` now covers actuator on 8080 (fail closed). Do not touch `/webhooks/psp` or `/v1/**`
   semantics.
3. Prod-like profile: `logging.structured.format.console=ecs` (+ `logging.structured.format.file=ecs`
   if a file appender is profile-active). Dev profile unchanged. No new logging dependencies (Boot 4
   built-in structured logging ONLY — a new dep here = STOP/P2).
4. Dockerfile `HEALTHCHECK` → `wget/curl http://localhost:9090/actuator/health`; compose healthchecks
   likewise; compose does NOT publish 9090; NGINX untouched.
5. `ManagementPortIT` (prod-like): main port `/actuator/health` → denied (assert the exact status the
   chain produces — 401/403/404 per denyAll semantics; no permissive fallback); management port:
   health 200 UP (no details) + `/actuator/prometheus` returns exposition text containing
   `jvm_memory_used_bytes` (registry live even before business metrics).

## S1 — JSON-log proofs (`JsonLogCorrelationIT`)

Prod-like profile, log capture in-test (ListAppender on the console encoder's logger context — capture
the STRUCTURED output; if Boot's structured encoder bypasses ListAppender, capture stdout via
system rule or assert against a memory appender configured with the same ECS encoder — pick ONE
approach and say which in the handoff).

- (a) Seed → POST `/v1/payments` with `X-Request-Id: <generated>` → parse emitted lines as JSON → the
  echoed request_id appears in the intake line; txid + merchant_id appear where the context holds them.
- (b) Webhook confirm → relay → ledger ingest logs carry the SAME request_id (it lives in the outbox
  envelope since E5 — this leg proves the read side surfaces it).
- (c) Scrubbing legs: run an invalid-key webhook + a valid request with `Authorization: Bearer
  <raw-key>` + a boot with the default DB password: NO emitted line contains the raw key material,
  the bearer value, or `dargent/dargent`. (MDC/params discipline — assert over ALL captured lines.)
- Zero sleeps: Awaitility with short poll on captured-line counts is acceptable; fixed Clock where
  timing is asserted.

## S2 — On-call drill (`OnCallTxidDrillIT` + `docs/runbooks/on-call-diagnosis.md`)

Seed: payment PENDING with due retry (pendular — the E9 exhausted/requeue era made this state real).
The drill mirrors a human at 03:00: (1) search emitted logs by txid → find the trail (status, last
transition, request_id, next_attempt_at); (2) re-search by request_id → full request trail. Assert
both searches resolve from the captured lines and account the operator budget via Clock arithmetic
(≤2 min modeled) — no wall-clock assertions. The runbook snippet (≤30 lines) reproduces the drill with
`docker logs` + `jq` for a real operator; commit it — it is part of the acceptance.

## S3 — Production lockdown (`ProductionLockdownIT`)

Prod-like profile asserts: `/v3/api-docs`, `/swagger-ui/**` → absent (assert the exact 404-family
status); actuator NOT on main (already covered in S0 — one regression assertion here);
`/actuator/health` on management port shows `"status":"UP"` with NO detail groups; POST
`/v1/payments` without key → 401; with a merchant key → 201; with another merchant's key on
`GET /v1/payments/{txid}` → 404/403 per E3 §3.7 (whichever the as-built returns — assert AS-IS and
name it).

## Handoff (API-audited — DOD §1 block MANDATORY)

`git log --oneline <base>..HEAD` + `git status --porcelain` + `gh run list --limit 5` (pasted) +
surefire `Tests run` summary for every cited class (pasted) + per-class `grep -c "@Test"` if counts
are claimed + the S0 ECS sample line (one raw JSON line pasted) + one `/actuator/prometheus` excerpt
pasted. Pairs = number AND id. Reds IN the table. First handoff of this epic carries the **TD-30
acknowledgment** (owner disposition). Then STOP — Block 2 (metrics + docs truth + E11 flip + citation)
is commissioned only after this channel's audit.

STOP conditions: P1 security-regression pressure · P2 logging dep/async temptation · P3 rename
pressure · P4 scope creep · P5 docs-vs-config divergence · P6 evidence discipline (DOD).
## Addenda (§9d — engineer questions, adjudicated by owner channel)

1. **Log capture for S1 (Q12):** `OutputCaptureExtension` (Boot-native stdout capture,
   `org.springframework.boot.test.system.CapturedOutput` — ships in spring-boot-test, ZERO new deps).
   Rationale: `ListAppender` hooks the logger context PRE-encoder — it would capture `ILoggingEvent`s
   and test the MDC, not the wire. The contract being proven is the EMITTED ECS line; only stdout
   capture tests the real encoder output. Say "OutputCaptureExtension" in the handoff. Parse captured
   stdout line-by-line as JSON (skip non-JSON noise lines).
2. **Scrubbing DB-password leg (Q13):** NO deliberately-failing boot with `dargent/dargent` — that
   tests failure plumbing, not scrubbing, and pollutes the suite with a broken-context leg. Instead:
   run the NORMAL context (ServiceConnection container generates REAL credentials), read the in-use
   password from the container's resolved properties, and assert ZERO occurrences of that actual
   secret across ALL captured lines. Stronger than default-credential theater: proves "the password
   in use never reaches the logs". The `dargent/dargent` prod fail-fast is the E13 rider (N3),
   explicitly out of E11 scope.
3. **Drill seed (Q14):** hybrid — deterministic where state must be forced, real where the trail must
   exist: create the payment VIA THE API (real request_id + intake lines are part of the trail);
   force due-state by direct UPDATE of the reconciliation schedule/expires columns (house precedent —
   every IT seeds/adjusts rows directly); then run ONE real reconciliation cycle against a PSP stub
   returning OPEN (no confirm) → the ladder-advance lines are emitted by the real use case. Do NOT
   drive publisher-failure loops (that is E9's exhaustion IT territory — coupling the drill to
   exhaustion semantics buys nothing for the operator's search problem).
4. **Management port test boot (Q15):** real dual-port boot — main on `RANDOM_PORT`, management via
   `management.server.port=0`, inject with `@LocalManagementPort` IF the annotation exists in Boot
   4.1 (grep the classpath first — zero-from-memory; package moved across Boot versions). If absent,
   resolve the management port from the Environment after startup. Assert EXACT statuses as-built and
   NAME them in the test (e.g., denyAll-on-anonymous resolves through the authentication entry point →
   whatever the chain produces — assert what IS, per house rule).

Plan confirmed: S0→S3 commits, then handoff with the full DOD §1 block + TD-30 acknowledgment.
## Block 2 COMMISSIONED (post-audit, 2026-09-04) — order binding

0. **S1 remediation FIRST (one commit):** make `CapturingAppender` format events through the ECS
   encoder (or attach Boot's structured encoder to the capture path) so captured text IS the wire
   format; rewrite JsonLogCorrelationIT legs (a) correlation fields, (b) end-to-end request_id,
   (c) scrubbing over ALL captured FORMATTED lines; extend OnCallTxidDrillIT to resolve the trail
   from those emitted lines (DB rows may complement, not replace). Paste 2 REAL ECS lines in the
   handoff. S1 is the epic's namesake — the wire is the contract.
1. S4 metrics (spec §5, names FROZEN; `MetricsScrapeIT`).
2. S5 docs truth pass + TD-22 + E11 ✅ flip (M4 ◐ preserved) as last content commit + exactly one
   citation commit citing a run whose tree IS the flip. DOD §1 evidence block in both handoffs.
