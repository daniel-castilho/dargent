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
