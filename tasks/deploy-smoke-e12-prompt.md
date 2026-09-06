# E12 Prompt — Deploy & Runtime Smoke (the epic where Dargent leaves the laptop)

**Milestone state at emission:** M0 ✅ · M1 ✅ · M2 ✅ · M3 ✅ · M4 ◐ (E11 ✅ closed; **E12+E13
complete M4**; E14 closes v1.0.0). E12 = the deployable epic: blue-green cutover, canary, rollback,
runtime smoke in CI — plus the owner-adjudicated harvest riders from the 4th external analysis.

## What this epic is (and is not)

Design is DONE — this is a landing epic. Contracts, in authority order:
1. `docs/design.md` — blue-green by immutable tag, 10%/30s canary, instant rollback, shutdown-under-load
   gate (§11), Flyway expand-only D16 (old color must survive the new schema).
2. `README.md` CI/CD section — "Pipeline at M4 (target)": runtime smoke (E2E happy path + reconciliation
   chaos + graceful shutdown under load). This epic makes those lines TRUE.
3. `docs/observability.md` §5/§7 + E11 P4 precedent — scraping topology stays minimal: ONE
   profile-gated Prometheus in compose scraping `api:9090`. Dashboards/alerting/tracing remain OUT (E15
   / trigger-activated).
4. `docs/handoff-dod.md` — contractual (counts from surefire or anchored `@Test$`; pairs number AND id;
   report state + gaps; owners quoted, never paraphrased).

## Pre-adjudicated (owner channel, 2026-09-05)

- **Zero new envs** (§4.1): deploy/rollback take ARGS (`deploy.sh <tag> [--canary 10,30,100]`), not
  environment. The compose `metrics` profile is a compose-level profile, not an app env.
- **One image, one tag, both colors**: blue/green run the SAME Postgres; cutover requires expand-only
  migrations (D16) — deploy.sh GATES on a migration-diff check (no DROP / type narrowing / column drop
  in the release diff) and aborts otherwise (report exact files).
- **The synthetic probe is ONE script used twice** (N10): `scripts/smoke.sh` = create→pay→webhook→GET
  CONFIRMED (+txid echo + request_id present). It is (a) the core of the CI runtime-smoke job and
  (b) the post-cutover probe inside deploy.sh. One probe, two consumers — no drift.
- **Riders landing here:** N8 (daily proof job + `dargent_ledger_proof_fail_total`), N12 (histogram SLO
  buckets + bucket assertions), N7 (log-level semantics doc), N9 ("Deferred by design" note with
  adoption triggers). Details in spec §5.

## Blocks

- **Block 1** (this emission): S0 deploy artifacts (deploy.sh/rollback.sh/canary weights/migration gate)
  → S1 blue-green drill evidence (script-exercised on compose; runbook §release-runbook updated) →
  S2 runtime-smoke CI job (compose up + smoke.sh + chaos leg) → S3 shutdown-under-load gate.
- **Block 2** (after Block-1 audit): S4 riders (N8/N12) → S5 doc riders (N7/N9 + release-runbook truth)
  → **E12 ✅ flip** (M4 ◐ preserved — completes with E13) + citation.
