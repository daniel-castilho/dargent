# E11 Prompt — Observability (the epic where Dargent learns to see itself)

**Milestone state at emission:** M0 ✅ · M1 ✅ · M2 ✅ · **M3 ✅** (E5+E8+E9 chain, register round 6)
· **M4 ◐** (completes with E11+E12+E13; E14 closes v1.0.0). E11 is the FIRST epic of M4 — the maturity
assessment's weakest pillar (Observable 1.5/5) and the one every external analysis ranked lowest.

## What this epic is

Design is DONE and frozen — E11 is a *landing* epic, not an invention epic. Contracts, in authority order:

1. `docs/design.md` §9 — pillars: Boot 4 structured JSON (ECS profile, zero dependencies); correlation
   via existing `X-Request-Id` filter (LIVE since E3 — verified); Micrometer + `/actuator/prometheus`;
   liveness/readiness; tracing OUT (non-goal).
2. `docs/observability.md` — the metric table: **8 metrics with frozen names/tags** (§5 of this spec
   mirrors them verbatim; renaming a documented metric = STOP/P3, owner decision).
3. `docs/slos.md` — S5 (`dargent_reconciler_confirmations_total`) and S6 (`dargent_outbox_lag_seconds`)
   are SLI-backed: after E11 their "Source of truth" column becomes TRUE.
4. Production lockdown (design.md §8 last row): Swagger/api-docs absent, actuator minimal on an
   **isolated management port**, API key mandatory on business endpoints — IT-proven here.

## Pre-adjudicated (owner channel, 2026-09-04)

- **Zero migrations.** Metrics read state via `JdbcClient` (outbox lag gauge) and counters at EXISTING
  decision points. No schema, no V-numbers (V113 stays the next payments slot).
- **Exactly ONE new env** (`§4.1`): `DARGENT_MANAGEMENT_PORT` (default `9090`). The actuator moves
  WHOLLY to the management port; Dockerfile `HEALTHCHECK` and compose healthchecks are updated in the
  same epic; NGINX never routes 9090; main port keeps `denyAll` (actuator matchers removed).
- **JSON ECS logs** via Boot 4 structured logging (`logging.structured.*`, ECS format, prod-like
  profile) — zero new dependencies. Log fields per line: `request_id`, `txid`/`aggregate_id`,
  `merchant_id` where the context has them.
- **Riders landing here:** TD-22 (README stack row becomes TRUE at the S5 flip — not before), and the
  log-scrubbing assertion (3rd-analysis rider N4: Authorization / raw API keys / DB password never
  appear in emitted JSON).

## Evidence discipline (E9 lessons, now mechanical)

- `docs/handoff-dod.md` (IN-REPO since `06e953b`) is part of this contract: every number, sha and run
  id pasted from command output included in the handoff; counts from surefire or omitted; reds in the
  table; **owner sanction only when quoting the channel message**.
- Handoffs **report state + gaps** — closure/flip adjudication belongs to the owner channel.
- TD-30 acknowledgment is expected in the first handoff of this epic (owner disposition, recorded).

## Blocks

- **Block 1** (this emission): S0 foundation (port + dependency + logging config) → S1 JSON-log proofs
  (correlation + scrubbing) → S2 on-call drill → S3 lockdown. No metrics yet.
- **Block 2** (after Block-1 audit): S4 the 8 metrics → S5 docs truth pass + TD-22 resolution +
  **E11 ✅ flip** (M4 ◐ preserved) + citation.
