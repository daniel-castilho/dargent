# Twelve-Factor Compliance

Factor-by-factor audit of Dargent against the [twelve-factor methodology](https://12factor.net), with honest
deviations declared. Reviewed at each milestone; new deviations are declared, never discovered.

| # | Factor | How Dargent complies | Deviations / notes |
|---|---|---|---|
| I | **Codebase** | One repo, one deployable API + one simulator app; Maven multi-module tracks; deploys = immutable image tags | — |
| II | **Dependencies** | All dependencies declared in POMs; Maven wrapper; no system-global jars; containers pin base images by digest | — |
| III | **Config** | Environment-only contract in prod (`.env.example` documents every var); `ConfigValidator` fails fast at boot with an aggregated report (unresolved placeholders, short secrets, static AWS keys in prod) | Dev `application.yaml` carries dev-only placeholders by design; prod must override |
| IV | **Backing services** | Postgres and SNS/SQS attached via URL/endpoint config (`AWS_ENDPOINT_URL` for LocalStack); swapping LocalStack for real AWS is config, not code | — |
| V | **Build, release, run** | Strict separation in CI: build (jar+image by SHA) → release (tag = semver image + SBOM + GitHub Release) → run (blue-green deploy of an immutable tag). Rollback = previous tag | — |
| VI | **Processes** | The API is stateless — all state in Postgres; shares nothing; horizontally addable | *Declared nuance:* schedulers (relay, expiration, reconciliation, settlement) run in-process. Safe under overlap by design (conditional UPDATEs, `SKIP LOCKED`, unique constraints) — chosen over a separate worker process + distributed lock for v1 simplicity |
| VII | **Port binding** | Boot embedded server; app is self-contained; NGINX is a plain reverse proxy, not an app server | — |
| VIII | **Concurrency** | Scale-out = more fleet containers behind NGINX; request handling is stateless; SQS consumers are per-queue with idempotent processing; no sticky sessions | Vertical scale of a single JVM is the honest ceiling for v1; factor preserved in shape |
| IX | **Disposability** | `server.shutdown=graceful` with phase timeouts; fast startup (Flyway + queue provisioning are bounded); **shutdown-under-load drain test gates CI**; SIGTERM handled by the JVM/compose | — |
| X | **Dev/prod parity** | Same compose shape in dev and prod (Postgres 16, LocalStack SNS/SQS); same app image; prod profile only tightens (lockdown IT proves the exposure differences) | LocalStack ≈ real AWS, not equality — accepted and documented (SNS/SQS APIs are stable; behavior proof ITs guard the seams) |
| XI | **Logs** | Structured JSON to **stdout** (Boot 4 ECS profile); correlation id in every line; environment/collector owns shipping & retention | On-prem v1 ships to docker json-file + host retention; a collector (Loki/ELK) is a stretch, not a factor violation |
| XII | **Admin processes** | One-off/maintenance runs as code in the same image & env: Flyway migrations (boot + CLI), outbox republish script, DLQ requeue endpoint (audited), restore drill scripts, balance proof job | — |

## Declared non-goals (not deviations, choices)

- **No config server / secret manager:** environment + host secrets file; the threat model (single host,
  disk-held secrets, digest-pinned images) is documented in the release runbook.
- **No service discovery:** NGINX + Docker DNS (`resolver 127.0.0.11`) plays that role at fleet scale of two.

## Review notes

- Factors VI and X carry the only declared nuances — both are decisions with ADRs, not accidents.
- Any new architectural pattern that touches a factor updates this file in the same PR.
