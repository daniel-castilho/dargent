# Twelve-Factor Compliance

Factor-by-factor audit of Cobre against the [twelve-factor methodology](https://12factor.net), with honest
deviations declared. Reviewed at each milestone; new deviations are declared, never discovered.

**Status legend (as-built honesty, added 2026-08-29 after external review):**
✅ operating in the tree today · ◐ partial (mechanism exists, scope grows) · 📋 **planned** — documented target
with a named milestone, **not yet written**. A factor is only ✅ when the code proves it.

| # | Factor | How Dargent complies | Status | Deviations / notes |
|---|---|---|---|---|
| I | **Codebase** | One repo, one deployable API + one simulator app; Maven multi-module tracks; deploys = immutable image tags (semver tags: 📋 M4) | ✅ | — |
| II | **Dependencies** | All dependencies declared in POMs; Maven wrapper absence declared; containers pin base image *versions* (digest-pinning: 📋 M4) | ✅ | — |
| III | **Config** | Environment-only contract (`.env.example`); prod must override dev defaults | ◐ | **`ConfigValidator` is 📋 planned (M1/M4)** — does not exist in the tree yet; today the app boots with dev defaults |
| IV | **Backing services** | Postgres attached via URL config; LocalStack endpoint via `AWS_ENDPOINT_URL` | ◐ | SNS/SQS clients arrive at **E6** — LocalStack runs empty by design today |
| V | **Build, release, run** | CI builds jar+image by SHA today | ◐ | Release tiers (semver tag → image + SBOM + GitHub Release) are 📋 **M4/E14** |
| VI | **Processes** | API stateless; all durable state in Postgres | ◐ | *Declared nuance:* schedulers (relay/expiration/reconciliation) run in-process from **E5/E6** — safe under overlap by design; **no scheduler exists yet** |
| VII | **Port binding** | Boot embedded server; NGINX as plain reverse proxy | ✅ | — |
| VIII | **Concurrency** | Scale-out = more fleet containers behind NGINX; stateless request path | ◐ | Blue-green fleets exist as compose topology; canary flip scripts are 📋 **M4/E12** |
| IX | **Disposability** | `server.shutdown: graceful` configured | ◐ | Shutdown-under-load drain test gating CI is 📋 **M4/E12** |
| X | **Dev/prod parity** | Same compose shape in dev and prod; same app image | ✅ | LocalStack ≈ real AWS, not equality — accepted and documented (behavior-proof ITs at E6) |
| XI | **Logs** | Structured JSON to stdout via Boot 4 ECS profile | ◐ | Correlation filter (`X-Request-Id` MDC) is 📋 **E11**; today logs are Boot defaults + graceful shutdown config |
| XII | **Admin processes** | Flyway migrations run at boot | ◐ | Outbox republish script, audited DLQ requeue endpoint, restore-drill scripts are 📋 **E9/E14** — cited in runbook as targets, not present yet |

## Declared non-goals (not deviations, choices)

- **No config server / secret manager:** environment + host secrets file; the threat model (single host,
  disk-held secrets, digest-pinned images) is documented in the release runbook.
- **No service discovery:** NGINX + Docker DNS (`resolver 127.0.0.11`) plays that role at fleet scale of two.

## Review notes

- Factors VI and X carry declared design nuances; factors III–V and VIII–XII carry 📋 planned mechanisms —
  every one of them has a named epic/milestone and an acceptance-matrix row where it must be *proven*, not
  just written.
- **Rule added after external review (2026-08-29):** this file may never describe a mechanism as compliant
  before its proving test exists. Any new architectural pattern that touches a factor updates this file in
  the same PR — with the status marker, not without it.
