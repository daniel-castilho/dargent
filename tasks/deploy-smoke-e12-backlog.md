# E12 Backlog — Deploy & Runtime Smoke

Epic goal: from an annotated tag to a running new color with a 10%/30s canary, instant rollback, and a
CI job that boots the whole compose stack and proves the money path — every commit, not on release day.

> **Status — Block 1 SHIPPED (S0–S3).** Evidence in `docs/release-runbook.md` "Corrections from the S2
> replicate (E12 S2), 2026-09-06 (binding)" + `deploy-smoke-e12-spec.md` §7. S4/S5/S6 remain **Block 2**.

```
E12 Deploy & Runtime Smoke (M4)
├── S0  Deploy artifacts: deploy.sh, rollback.sh, canary weights, migration gate     [Block 1 ✅]
├── S1  Blue-green drill on compose (script-exercised, evidence recorded)            [Block 1 ✅]
├── S2  Runtime-smoke CI job: compose up + smoke.sh + reconciliation chaos leg        [Block 1 ✅]
├── S3  Shutdown-under-load gate (graceful, assert zero dropped in-flights)           [Block 1 ✅]
├── S4  Riders N8 (daily proof + counter) + N12 (SLO buckets + assertions)            [Block 2]
├── S5  Doc riders N7/N9 + release-runbook truth + E12 ✅ flip + citation              [Block 2]
└── (S6) Optional: profile-gated compose Prometheus scraping api:9090                 [Block 2, tiny]
```

### S0 — Deploy artifacts (Block 1)
- `scripts/deploy.sh <tag> [--canary 10,30,100]`: verify tag exists → migration-diff gate (S0-gate
  below) → start new color (compose project suffix) → healthcheck via MANAGEMENT port (E11 contract:
  `:9090/actuator/health`, liveness+readiness) → shift NGINX weights stepwise (10% → observe 30s →
  next) → 100% new → stop old color after drain. Every step echoes what it does; abort leaves the old
  color at 100% (fail-closed cutover).
- `scripts/rollback.sh`: weights back to previous color instantly (no rebuild); prints the incident
  one-liner for `docs/release-runbook.md`.
- **S0-gate (migration diff):** `git diff --name-only <last-release>..<tag> -- '**/db/migration/**'` →
  fail on `DROP `, `RENAME `, `ALTER COLUMN .* TYPE`, `NOT NULL` additions on existing columns
  (D16 expand-only is the machine-checked contract, not folklore).
- Canary weights: nginx conf becomes a small template rendered by deploy.sh (weights are the only
  variable; upstreams/keepalive unchanged).
- **Accept:** script `--check` mode prints the plan without executing; shellcheck clean (or documented
  why a suppression exists).

### S1 — Blue-green drill (Block 1)
- Exercise ONCE, locally/compose, the full path: deploy v_n → traffic → deploy v_{n+1} (canary 10/30/100)
  → rollback mid-canary → re-deploy to 100%. Record as a runbook section (commands + outputs pasted,
  DOD style) — `docs/release-runbook.md` gains "E12 drill evidence".
- **Accept:** drill evidence committed in S1's commit; every abort path demonstrated at least once
  (healthcheck fail → abort; migration gate fail → abort).

### S2 — Runtime-smoke CI job (Block 1)
- New job `runtime-smoke` (needs: build): docker compose up (app + postgres + localstack + nginx) →
  wait readiness (management port) → `scripts/smoke.sh` (create 2 payments — one idempotent replay →
  webhook CONFIRMED → GET asserts status+fee+request_id) → **chaos leg**: suppress the webhook for one
  payment (PSP stub flag) → run reconciler once → GET CONFIRMED (the E5 signature scenario, now against
  the deployed stack) → compose logs archived as artifact on failure.
- **Accept:** job green on the epic's own PR chain; flaky-once = rerun once + label; flaky-twice = STOP
  (P4) — the smoke job must be trustworthy or it is noise.

### S3 — Shutdown-under-load (Block 1)
- In the same job (or a second step): generate steady load (the smoke script in a loop) → SIGTERM the
  app → assert: no in-flight create/webhook failed with connection refused AFTER drain started; final
  GETs reconcile; app exits ≤ 30s (graceful shutdown contract).
- **Accept:** recorded in the job log with the assertion output; CI gate fails on violation.

### S4 — Riders (Block 2)
- **N8:** (a) scheduled workflow `proof-daily` (cron) booting the stack and running the existing proof
  path (`/v1/ledger/proof` + ΣDR=ΣCR + projection==lines ITs) — exit status IS the S7 source of truth;
  first execution via `workflow_dispatch` (proof it runs); (b) counter `dargent_ledger_proof_fail_total`
  incremented on the proof endpoint's failure path (tagged `scope=balance|projection`); assert in
  MetricsScrapeIT extension (+1 method, seeded failure impossible → assert series EXISTS at 0 —
  presence assertion, never a seeded failure in tests).
- **N12:** `management.metrics.distribution.slo.http.server.requests: 100ms,250ms,1s` +
  `publish-percentile-histogram: true`; MetricsScrapeIT extension asserts
  `http_server_requests_seconds_bucket{le="0.25"}` present. (slos.md thresholds become buckets — that
  is the whole rider.)

### S5 — Doc riders + flip (Block 2)
- **N7:** observability.md §2 gains the level contract: `WARN` = degraded-but-self-healing (retry
  scheduled, backoff engaged), `ERROR` = needs a human (poison, exhausted, proof failure). Spot-check:
  grep money-path failures currently logged INFO/ERROR against the contract; fix mismatches that are
  one-line changes; anything structural → report, don't refactor.
- **N9:** observability.md §7 (non-goals) gains: "Deferred by design — distributed tracing, exemplars,
  tail sampling. Adoption triggers: (a) ledger/relay extracted as a second process, (b) p95/p99
  inexplicable from logs+metrics, (c) multi-host. Correlation (request_id+txid crossing the outbox
  boundary) is the prerequisite and is already enforced by IT."
- release-runbook.md: reflect the real deploy.sh/rollback.sh flow (replace any aspirational text —
  P5 discipline).
- **Flip:** epics E12 ✅ (real run chain; **M4 ◐ preserved — completes with E13**) as LAST content
  commit; exactly ONE citation commit after; nothing later.

### S6 (optional, tiny) — compose `metrics` profile: single Prometheus scraping `api:9090`; smoke job
curls the Prometheus targets API asserting `dargent_*` ingestion. If it fights the job, cut it and
report (it is a convenience, not a contract).
