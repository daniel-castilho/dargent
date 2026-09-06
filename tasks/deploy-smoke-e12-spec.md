# E12 Spec — Deploy & Runtime Smoke (exact contracts)

Authority: design.md §11 > README CI/CD (M4 target lines) > this file (implementation precision).
Where release-runbook.md disagrees with the scripts, the SCRIPTS win and the runbook gets fixed (S5).

## §1 Scope

Deploy artifacts (deploy.sh/rollback.sh/canary weights/migration gate), blue-green drill evidence,
runtime-smoke CI job (compose + synthetic probe + chaos leg), shutdown-under-load gate, riders
N8/N12/N7/N9. Out of scope: k8s/Terraform/cloud LB (P2), dashboards/alerting (E15), tracing
(trigger-activated), SBOM/tag releases (E14).

## §2 Deploy contract

- `scripts/deploy.sh <tag> [--canary 10,30,100]` — default canary `10,30,100` (design contract).
- Preconditions, each fail-closed: (1) tag exists; (2) **migration gate**: no D16-violating migration in
  `<last-release>..<tag>` (patterns: `DROP `, `RENAME `, `ALTER COLUMN` + `TYPE`, `SET NOT NULL` on
  existing columns); (3) new color reaches READY on the management port (`:9090/actuator/health`,
  readiness group) within the compose healthcheck budget.
- Cutover: NGINX weights per canary steps, minimum 30s dwell between steps (design: 10%/30s), probe
  `scripts/smoke.sh` against the LIVE stack after each weight bump; any probe failure or readiness
  flap → abort → old color 100% (weights restored), new color stopped after drain, exit non-zero with
  the failing step named.
- `scripts/rollback.sh` — instant weights-to-previous-color, no rebuild, prints the incident one-liner.
- NGINX conf = template (weights variable only). Compose project naming must allow BOTH colors running
  concurrently against ONE Postgres (expand-only makes this safe — D16).

## §3 CI contract

- Job `runtime-smoke` (needs build): compose up → readiness wait (management port, never :8080) →
  `scripts/smoke.sh`: create (201 + txid) → idempotent replay (byte-equal response, BD-6) → webhook
  CONFIRMED → GET (status/fee/request_id asserted) → **chaos leg**: PSP stub suppresses webhook →
  reconciler `runOnce()`-equivalent → GET CONFIRMED. Logs archived on failure (artifact).
- Shutdown-under-load: loop the probe → SIGTERM → zero connection-refused after drain begins → final
  GETs consistent → process exit ≤ 30s. Assertion output pasted in the job log.
- Flaky policy: one rerun per push, labeled; two reds without cause = STOP (P4).

## §4 Proof & buckets riders

- **N8 proof job:** workflow `proof-daily`, cron daily + `workflow_dispatch`; runs the stack + the
  proof suite (ΣDR=ΣCR, projection==lines, `/v1/ledger/proof`). Exit status = S7 source of truth.
  Counter `dargent_ledger_proof_fail_total{scope=balance|projection}` on the proof endpoint failure
  path; presence-at-zero asserted in the metrics IT (never seed a failure to make it non-zero).
- **N12 buckets:** `management.metrics.distribution.slo.http.server.requests: 100ms,250ms,1s` +
  percentiles-histogram true; IT asserts `http_server_requests_seconds_bucket{le="0.25"}` present in
  the scrape. slos.md thresholds = bucket edges (one config line, one assertion — the whole rider).

## §4.1 Environment contract

**Zero new envs.** Deploy/rollback take args; compose `metrics` profile is compose-level; NGINX weights
are rendered by deploy.sh from args. Any new env name = STOP (owner adjudication; §4.1 is contract).

## §5 Doc riders (land in Block 2 — exact text supervision)

- **N7** (observability.md §2): `WARN` = degraded-but-self-healing (retry/backoff engaged — ladder
  advance, reconciliation reschedule); `ERROR` = needs a human (poison → DLQ, EXHAUSTED, proof failure,
  bean-ambiguity-class boot faults). Money-path spot-check: fix one-liners; report the rest.
- **N9** (observability.md §7): "Deferred by design — tracing/exemplars/tail sampling. Triggers:
  (a) second process (ledger/relay extraction), (b) p95/p99 inexplicable from logs+metrics,
  (c) multi-host. Prerequisite already IT-enforced: request_id+txid cross the outbox boundary."
- release-runbook.md rewritten to match the scripts (S1 drill evidence linked from it).

## §6 Acceptance matrix (skeleton — executor fills with pairs)

| Item | Deliverable | Test / Evidence | CI Run | Status |
|---|---|---|---|---|
| S0 | deploy/rollback/gate | `--check` plan + shellcheck + drill | pair | ✅ |
| S1 | blue-green drill evidence | runbook section (outputs pasted) | pair/— | ✅ |
| S2 | runtime-smoke job | job log + chaos leg | pair | ✅ |
| S3 | shutdown-under-load | job assertion output | pair | ✅ |
| S4 | proof cron + counter + buckets | workflow run + scrape assertions | pair | ◻ |
| S5 | docs truth + E12 ✅ (M4 ◐) + citation | epics diff | pair | ◻ |

## §7 Block 1 evidence (S0–S3, single push — quoted at the commit it ran at, TD-10)

| Item | Deliverable | Evidence | Verdict |
|---|---|---|---|
| S0 | `deploy.sh`/`rollback.sh`/canary weights/migration gate | D2′ migration-gate abort on `e644484` (gate flagged V110/V205/V207); D3′ canary `v0.3.0` 10→30→100 each `SMOKE PASS`, cutover, blue drained/stopped (`DEPLOY OK`, rc=0); D4′ rollback after full cutover (`ROLLBACK OK`, rc=0) — pasted in `docs/release-runbook.md` "Corrections from the S2 replicate (E12 S2), 2026-09-06 (binding)" | ✅ |
| S1 | blue-green drill evidence | runbook D1–D6 original + D2′/D3′/D4′ reruns on the fixed nginx mechanism; block/lift cycle 500↔503 verified with plain reloads | ✅ |
| S2 | runtime-smoke job | three consecutive `RUNTIME-SMOKE PASS (P0–P6)` rc=0 (23:48, 23:03, 23:32 local — last on the webhook-500 fix); P4 chaos `9KBEDOB0DZHA8RHIWZOLL0PCQ` CONFIRMED by reconciler, zero `webhook_events` rows | ✅ |
| S3 | shutdown-under-load | P5 probe codes `4 200 35 502 1 504` (single 504 is a drain-window artifact, disclosed); zero connection-refused; fleet restored P6 | ✅ |
| S3.1 | webhook intake fail-closed defect (E12 S3 follow-up) | `POST /webhooks/psp` urlencoded/opaque now 401 `invalid_signature` (was 500) + audit row persisted; valid-sig + unparseable body → 400 `invalid_request` + row `signature_valid=true`; `WebhookIntakeIT` 13/13 | ✅ |
