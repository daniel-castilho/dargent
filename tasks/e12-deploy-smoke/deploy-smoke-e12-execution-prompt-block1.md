# E12 — Execution Prompt, Block 1 (S0–S3)

Engineer brief. Contracts: `deploy-smoke-e12-spec.md` (this package) + design.md §11 + README M4 lines
+ `docs/handoff-dod.md`. Sequence + stops: `deploy-smoke-e12-sequence.md`. Deliver S0→S3, then STOP and
report (state + gaps — never closure claims). **Zero new envs** (§4.1): scripts take ARGS; a new env
name = STOP. Zero migrations expected (the migration GATE is code, not a migration).

## S0 — Deploy artifacts (scripts + nginx template + gate)

1. `scripts/deploy.sh <tag> [--canary 10,30,100]`:
   - fail-closed preconditions in order: tag exists → migration gate (`git diff --name-only
     <last-release>..<tag> -- '**/db/migration/**'` then grep for D16 violations: `DROP `, `RENAME `,
     `ALTER COLUMN`+`TYPE`, `SET NOT NULL` on existing columns — print the offending lines and abort) →
     start new color → wait READY on `:9090/actuator/health` (readiness; budget = compose healthcheck
     interval × 3, named constant) → canary weight steps with ≥30s dwell → probe `scripts/smoke.sh`
     after each bump → 100% → stop old color after drain.
   - ANY failed precondition/step → abort path: old color stays/restores at 100%, new color drained and
     stopped, non-zero exit, offending step named. Fail-closed cutover is the contract (P1).
   - `--check` mode: print the plan (steps, weights, migration-gate verdict) without touching anything.
2. `scripts/rollback.sh` — instant weights-to-previous-color, no rebuild, incident one-liner printed.
3. NGINX conf → template: ONLY the two `weight=` values are variable; upstreams/keepalive/proxy
   settings byte-stable.
4. `scripts/smoke.sh` (shared with S2 — ONE probe, two consumers): create → idempotent replay
   (byte-equal, BD-6) → webhook CONFIRMED → GET asserts status/fee/request_id; takes base URL + key as
   args; exits non-zero naming the failed leg.

## S1 — Drill evidence (same commit as S0 if clean)

Run the full path on compose: deploy tag_n → traffic → deploy tag_{n+1} canary 10/30/100 → rollback
mid-canary → redeploy to 100. Also demonstrate BOTH abort paths once each (healthcheck fail; migration
gate fail). Evidence = commands + outputs pasted into a new "E12 drill evidence" section of
`docs/release-runbook.md` (DOD style — raw, not narrated).

## S2 — Runtime-smoke CI job

`.github/workflows/ci.yml` gains job `runtime-smoke` (needs: build): compose up (app+postgres+localstack
+nginx) → readiness on management port → `scripts/smoke.sh` → chaos leg: PSP stub suppresses webhook →
drive one reconciler cycle → GET CONFIRMED (E5 signature scenario against the deployed stack) → compose
logs archived as artifact ON FAILURE ONLY. Job red twice without written cause = STOP (P4). One rerun
per push allowed, labeled in the handoff.

## S3 — Shutdown-under-load

Same job, second step: probe loop under load → SIGTERM app → assert (a) zero connection-refused on
in-flight probes after drain began, (b) final GETs consistent, (c) process exit ≤ 30s (graceful
contract). Assertion output must appear in the job log verbatim.

## Handoff (DOD §1 — full block)

`git log --oneline <base>..HEAD` + `git status --porcelain` + `gh run list --limit 5` pasted; surefire
class summaries pasted for any class cited; for script/CI steps without unit tests, the RUN LOG excerpt
is the evidence (pasted); drill section link; the exact abort-path outputs (both). Then STOP — Block 2
(riders N8/N12 + doc riders N7/N9 + flip + citation) is commissioned only after this channel's audit.

STOP conditions: P1 cutover-safety pressure · P2 infra creep · P3 observability creep · P4 flaky smoke ·
P5 docs divergence · P6 evidence discipline.
