# Release Runbook

Operating procedures for releasing, deploying, rolling back, and restoring Dargent on the on-premises host.
If a procedure here is wrong, fix it in the same PR that discovered the fact.

---

## 1. Artifacts & promotion flow

- Every commit on `main`: CI builds **the** jar and **the** image `ghcr.io/<org>/dargent-api:sha-<short7>`
  (immutable) + `:edge` (moving). A commit always maps to the same jar + image.
- Annotated tag `vX.Y.Z` (cut when a milestone meets DoD): CI re-runs all gates on the tagged commit, pushes
  the semver image, and opens the GitHub Release with the jar + CycloneDX SBOM of the exact shipped image.
- Deploys **only by immutable tag** — never `edge`, never `latest`. Maven version stays `1.0-SNAPSHOT` in dev.

## 2. Pre-release checklist

1. Milestone acceptance matrix filled with evidence; deviations declared in AGENTS.md §8.
2. `docs/releases/vX.Y.Z.md` written; CHANGELOG updated; lessons reviewed.
3. **Migration review (expand/contract)**: any schema change validated against the two-release checklist —
   release N+1's migrations keep release N runnable, and vice versa. Blue and green share the database.
4. Restore drill current (quarterly, §6) — a backup chain you haven't restored is a hope, not a backup.

```bash
git checkout main && git pull
./mvnw clean verify                     # local gate before tagging; CI re-runs everything anyway
git tag -a vX.Y.Z -m "Release X.Y.Z: <one-line summary>"
git push origin vX.Y.Z
```

## 3. Deploy (blue-green, health-gated canary)

Topology: NGINX :8080 → `api-blue` :8081 / `api-green` :8082 (one fleet active, other idle).

```bash
scripts/deploy.sh v1.2.3        # target fleet = currently idle color
```

What the script does (operator contract, in order):

1. Pull the exact tag; record image digest in `deploy/runtime/last-deploy.txt`.
2. **Readiness gate**: start the idle fleet (compose), poll `/actuator/health/readiness` (bounded loop, logs on timeout — abort leaves the old fleet untouched). Readiness covers Postgres + LocalStack.
3. **Canary**: flip NGINX runtime conf copy to 10% new / 90% old, `nginx -t` + reload, observe 30 s:
   error rate, p95, `dargent_outbox_lag_seconds`, DLQ depth. Any red signal ⇒ **automatic abort to 100% old**.
4. **Cutover**: 100% new; old fleet gets `server.shutdown=graceful` drain (in-flight requests finish).
5. Stop the old fleet; record the release in the deploy log; post-verify (§4).

Gotchas baked in (each cost a real team an afternoon — see lessons.md #9, #10):
`down` instead of `weight=0`; `resolver 127.0.0.11 valid=10s` + `zone`/`resolve` on upstreams so recreated
containers are picked up; passive checks `max_fails=3 fail_timeout=10s`; `proxy_next_upstream error timeout`.

## 4. Post-deploy verification

```bash
curl -fs http://localhost:8080/actuator/health/readiness        # via NGINX
curl -sX POST localhost:8080/v1/payments -H "Authorization: Bearer $DARGENT_API_KEY" \
  -H "Idempotency-Key: smoke-$(date +%s)" -d '{"amount":100,"description":"deploy smoke"}'
# pay the QR at the simulator, poll status → CONFIRMED; ledger journal exists; outbox drains
```

Metrics glance: transitions ticking, outbox lag < 5 s, DLQ depth 0, no spike in signature failures.

## 5. Rollback

```bash
scripts/rollback.sh              # instant: flips NGINX back to the previous fleet (still warm or restarted)
scripts/deploy.sh v1.2.2         # deeper rollback: redeploy previous immutable tag through the same gate
```

Rollback never runs migrations backward (forward-only policy). Schema compatibility with release N is the
expand/contract contract — this is why it exists.

## 6. Backup, restore & drills

- **Backup:** nightly `pg_dump -Fc` + 15-minute WAL archival to the host's backup volume; rotation keeps 8.
  `deploy/systemd/dargent-backup.{service,timer}`.
- **RPO:** ≤ 15 min. **RTO:** ≤ 30 min (restore + boot + verify). Stated honestly; the drill measures it.
- **Restore procedure:** `scripts/restore.sh <dump>` — restore into a fresh cluster, run Flyway (no-op expected),
  boot the app, verify counts per table against the dump manifest, run the balance proof, release traffic.
  **Traffic never returns over an unverified restore** (script exits non-zero on any count mismatch).
- **Quarterly restore drill:** run the restore into a scratch port; record date, dump age, verification output
  and wall-clock RTO in `docs/drills/restore-<date>.md`. The drill is the deliverable — a backup without a
  recorded restore is a hope.
- **LocalStack is disposable by design:** after a host loss, queues re-provision at boot; missed events replay
  via the outbox republish tool (`scripts/republish-outbox.sh --from <ts>`); nothing else is lost.

### Deploy drill record — S1 (E12 Block 1), 2026-09-06

Repo state: commit `e644484` + uncommitted S0 working tree (psp `psp_test_` 9-char prefix, deploy/rollback/smoke
scripts, Flyway-starter fix). Drilled ref: `v0.3.0` (last tag; gate `v0.3.0..v0.3.0` vacuous). Both colors on
the same compose image (`dargent-api:compose`). Smoke key: random `psp_test_` + 43 base62 chars, inserted
directly into `payments.api_keys` (SHA-256 hex hash, `key_prefix = 'psp_test_'`), same row for the whole drill.

| # | Drill | Result | Evidence (verbatim) |
|---|---|---|---|
| D1 | Baseline traffic (blue 100) | PASS | `SMOKE PASS` legs 1-4: create → idempotent replay → pay at simulator → CONFIRMED within 1s webhook poll |
| D2 | Migration-gate deny (real) | PASS | Deploy `e644484` ABORTED at precondition 2/3: gate flagged `V110` (ALTER COLUMN actor_key_id DROP NOT NULL), `V205` (event_id DROP NOT NULL), `V207` (DROP CONSTRAINT events_status_check) — all post-`v0.3.0`. Traffic stayed 100%: `70 200`. **Owner decision required before first real release** (see §9). |
| D3 | Full canary deploy (green) | PASS | `canary step 1/2/3: api-green weight=10/30/100`, `SMOKE PASS` after each bump, dwell 30s, `cutover complete` → old color drained/stopped. Traffic: `80 200`. |
| D4 | Rollback mid-canary | PASS | deploy killed at `canary step 1` (blue=10); `scripts/rollback.sh` → `rolling api-blue -> api-green … api-green back to 100%; api-blue now down`. Traffic: `90 200`; post-rollback `SMOKE PASS`. ⚠️ Evidence superseded for the weight-revert mechanics by the S2 correction below — rerun at 22:54. |
| D5 | Abort on readiness timeout | PASS | idle color crashed at boot → `DEPLOY ABORT: readiness TIMEOUT for api-green (api-blue stayed active)`, automatic restore to 100% + drain. Traffic: `30 200`. |
| D6 | Canary in reverse direction (blue) | PASS | Same 10/30/100 + smoke sequence. Traffic: `59 200 1 504` — the single `504` landed in the drain window (upstream closing during `compose stop -t 30`) → treat as known drain artifact, not a regression. |

Findings to disclose and keep in view:
- **Drain-window 504** (D6): the killed old color can yield a transient gateway error in the instant the old
  container stops; the canary gate itself never lost traffic. Options for Block 2: `proxy_next_upstream`
  off/on, or accept as the documented zero-gap limit.
- **Migration gate blocks the real nominal range**: `v0.3.0..e644484` contains three migrations the D16 gate
  flags (all are *relaxing* operations — `DROP NOT NULL`, `DROP CONSTRAINT` — safe forward, but the spec's
  `DROP ` pattern is intentionally fail-closed). Decide: (a) keep gate spec-exact and remediate with forward
  migrations, or (b) refine the gate to allow `DROP NOT NULL`/`DROP CONSTRAINT` while still denying
  `DROP COLUMN`/`RENAME`. Not resolved in-block (BD-14 discipline).
- Smoke deviations from spec wording (same class as the `fee` field): figure `expiresIn` is compared after
  masking, because the API recomputes it live (`Duration.between(now, expiresAt)`) — a stored byte-equal
  value would be stale by definition; everything else is verified byte-identical. Detailed idempotency: `expiresIn` masked, `expiresAt` byte-equal.

### Corrections from the S2 replicate (E12 S2), 2026-09-06 (binding)

**Single-file bind mount pins the inode — fixed by mounting the directory.** The original compose mount
was `./deploy/runtime/nginx.conf:/etc/nginx/nginx.conf:rw`. A single-file bind resolves the inode at mount
time; any atomic replace (`mv`, `sed -i`, editors) orphans the old inode and the container keeps reading
stale config, so `nginx -s reload` silently did nothing. The D4 rollback above used `mv` and therefore did
*not* revert the weights — the correct behavior only held because `deploy.sh` renders with truncate-in-place
and the pinned inode happened to match. Fix (adopted): mount the **directory** `./deploy/runtime:/etc/nginx/runtime:ro`
and start nginx with `command: nginx -c /etc/nginx/runtime/nginx.conf -g "daemon off;"`. Directory binds
resolve by name on every access — host writes now propagate to plain `nginx -s reload`. Research:
docker bind-mount inode pinning (E12 S2). Equal-named only.

**Rerun evidence on the fixed mechanism (all timestamps 2026-09-06, working tree `e644484` + S0/S2):**

| # | Drill | Result | Evidence (verbatim) |
|---|---|---|---|
| D2' | Migration-gate deny (real, rerun) | PASS | `DEPLOY 22:52:00 ABORT: migration gate FAIL` — gate flagged `V205`/`V207`/`V110` for `v0.3.0..e644484`; traffic untouched. Deploy drill then used `v0.3.0` (gate vacuous: `no migrations in v0.3.0..v0.3.0 — gate PASS`). |
| D3' | Full canary deploy (green, rerun) | PASS | `canary step 1/2/3: api-green weight=10/30/100` with `SMOKE PASS` after every bump (txids `ANWWN88LMHGY0ARWQE0DYXB51`, `I3QMW4GOX0L12QCNJQ7VOLKLV`, `Q56660ZTEXP033BRDCZDT4M5L`), dwell 30s each, `cutover complete (100% on api-green)`, `api-blue drained and stopped`; `DEPLOY OK` rc=0. Weights VERIFIED via functional probes on the live reload (directory mount). |
| D4' | Rollback after full cutover (rerun) | PASS | `rollback.sh` → `api-blue` STARTED (new: rollback restarts the drained old color), reload applied, `--active` → `api-blue`, `ROLLBACK OK` rc=0; post-rollback `SMOKE PASS` (create → CONFIRMED `4714a17b-…`, 1s webhook poll) proving traffic serves blue again. |

**S2/S3 runtime-smoke job (P0–P6) — first fully green run, 22:48–22:51:**
`RUNTIME-SMOKE PASS (P0-P6)` rc=0: P0/P1 stack+readiness, P2 key insert, P3 smoke (webhook confirm <1s),
P4 chaos webhook-suppression (`POST /webhooks/psp → 503` confirmed) → simulator pay → **reconciler confirmed
`0ARQX9SFVEZY9XUXJ03K6NACR` with zero `payments.webhook_events` rows** (self-healing without the webhook),
block lifted back to 500 — P5 shutdown-under-load (`stop in 1s, probe codes: 4 200 35 502 1 504` — the 502s/504
are nginx gateway artifacts during the drain window, the API answered consistently 4×200), P6 fleet restored.

Findings from the S2 replicate (+ lessons for design.md §11.2 / lessons):
- **nginx reload was a silent no-op under the file bind** — the whole S1/S2 chaos confusion traced back to it.
  Symptom pair: host/container inodes drift after `mv`; `container grep=0` while host grep=3; `nginx -t` OK but
  live config unchanged. Diagnostics: compare `stat -c %i` host vs `docker compose exec nginx stat -c %i`.
- **`awk file > file` truncates the source before awk reads it** — an in-place render on the SAME path zeroes the
  file silently ("no `events` section" on reload). Renders must land in a temp file, then `cat temp > file`.
  Guard: render + assert `grep -q '^events {'`.
- **Reconciler first-rung timing**: payment create schedules the first reconcile at `now + backoff[0]` (default
  60s); the scan interval only gates how often `runOnce` runs. A chaos webhook-drop confirms at ~60s, NOT the
  scan tick — smoke budgets must cover first rung + slack (90s), which the job now does.
- **A failed chaos leg must never leak its block**: the job installs an EXIT trap that idempotently strips
  chaos markers + reloads, so even a P4 failure restores normal webhook intake.

## 7. Incidents — quick reference

| Incident | First move | Then |
|---|---|---|
| Outbox lag climbing / `EXHAUSTED` rows | Check relay logs + LocalStack health | Fix cause; audited requeue endpoint for `EXHAUSTED`; verify drain |
| DLQ depth > 0 | Read the message (compose exec into LocalStack aws cli) | Fix the poison cause; requeue; if unknown, snapshot and escalate |
| Webhooks rejected en masse (`signature_expired`) | Clock drift check on simulator/host | NTP fix; reconciler catches the gap — verify it did |
| Ledger proof failed | **Freeze deploys** | Snapshot DB; triage journal vs projection; correcting entries (append-only) with ADR note |
| Blue-green canary abort | Automatic — confirm traffic 100% old | Read new-fleet logs; fix forward; redeploy by the book |

## 8. Host-loss & image-upgrade duties

- **Host loss:** reprovision from IaC-in-repo (compose files, systemd units, nginx conf), restore latest dump
  (§6), redeploy by tag. LocalStack state loss is expected and harmless (§6).
- **Image upgrades:** base images are digest-pinned; upgrades are PRs that change the digest, pass the full
  pipeline (non-root + Trivy gates re-run), and record the new digest here.
