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
