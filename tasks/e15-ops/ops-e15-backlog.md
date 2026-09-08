# E15 — Backlog (S0–S7)

Pattern identical to E11–E14: Q-batch first; each step = one PR (or tight pair); evidence verbatim
in PR body; STOP after Block 1.

## Q-batch adjudications (2026-09-08, owner delegated execution — "você decide e aprova")

| Item | Decision |
|---|---|
| Staging | Merge the tasks reorg PR first (became #13, merged `99ef945`); E15 PRs cut from `main` |
| Rate limit algorithm (S1) | Hand-rolled token bucket (zero dependencies — repo ethos); per-IP; Clock-injected |
| Epic docs in git | Commit `tasks/e15-ops/` in the S0 PR (E11–E14 precedent) |
| Grafana rider (S2) | Skipped — E16 candidate |
| PITR (S5) | Local-documented harness + drill record (no CI job) |
| DEBT-7 (S6) | Path A (consolidate with proof: floors identical); Path B = recorded fallback |
| Webhook route | Unchanged `/webhooks/psp` — the `/v1/` prefix in this package was a doc error (fixed in-tree) |
| Webhook abuse metric | New counter `dargent.webhook.rejections{reason=rate_limited\|body_too_large}` decided here, not silently — feeds the S2 alert rule |

---

## BLOCK 1

### S0 — Post-release truth pass (docs+yaml; the TD-35 closer)

1. `docker/compose.demo.yaml`: overlay with RELAY/LEDGER_CONSUMER/RECONCILER/EXPIRATION `=true`
   (the original E14 S6.5 rider, never landed). README line documenting
   `docker compose -f docker/compose.yaml -f docker/compose.demo.yaml up` and what it proves.
2. README cover: "E14 cuts v1.0.0" (future) → "E14 cut v1.0.0 (tag `v1.0.0` @ `601a669`)" — both
   the paragraph and the milestone-table cell.
3. `docs/epics.md`: MINT the E15 row (☐) — `| E15 | Operational hardening: alerts, load baseline,
   scale restore, PITR, webhook abuse controls, DEBT dispositions | api, deploy, docs | E11, E13,
   E14 | post-1.0.0 | ☐ |` (exact wording may adjust in Q-batch).
4. The E14 citation PR (#12) body reportedly says "104 ids" — verify against the actual job log /
   repo docs; correct or annotate; report the current evidence-lint scanned-id count.
   **RESOLVED (S0, 2026-09-08):** the claim is unverifiable in the record — PR #12's body (fetched
   verbatim via `gh`) contains no "104 ids" text, and a scan of all 40 repo PR bodies found zero
   occurrences. The E14-era true numbers are: 89 ids at the S0 relint (spec matrix, `7a416a3`),
   105 ids as of this epic's start (evidence-lint local run, this PR). No correction to any shipped
   artifact is required; the "104" figure existed only in this epic's own commissioning rumor.
5. Evidence: diff --stat docs+yaml-only; evidence-lint green.

### S1 — Webhook abuse controls — DEBT-8 real closure (code + ITs)

1. In-app **rate limit** on `POST /webhooks/psp` (Q-batch: algorithm — hand-rolled token bucket
   vs bucket4j dependency, with evidence; per-key and/or per-IP scope; defaults generous enough to
   never touch smoke/demo, tunable via env with safe defaults).
2. In-app **body cap** on the same route (Q-batch: default cap value; behavior = 413).
3. Order of controls carved in tests: oversize body → 413 BEFORE HMAC consumption; over-limit →
   429 with no money-path side effects; valid traffic under limit → unchanged.
4. ITs: `WebhookRateLimitIT` (burst → 429 → recovery window) + `WebhookBodyCapIT` (413) +
   regression proof that smoke/confirmer paths are unaffected (limits raised in test profile).
5. Docs same-PR: AGENTS §8 DEBT-8 → **RESOLVED** (commit + date); threat model surface-1 DoS row →
   mitigated in-app, NGINX = defense-in-depth; runbook §4 signature-failure glance gains the 429/413
   signals.
6. Evidence: IT run ids verbatim; the DEBT-8 row diff; a negative-path log line each for 429/413.

### S2 — Prometheus alert rules + promtool bite (+ optional Grafana rider)

1. Rule file(s) in the metrics profile covering at minimum (exact set via Q-batch):
   proof failure (`*_proof_fail_total` > 0), outbox lag above SLO, DLQ depth > 0,
   webhook signature-failure burst, EXHAUSTED backlog. Each rule: name, expr, `for`, severity
   label, **runbook anchor annotation** (the response line an on-call would open).
2. `promtool test rules` cases for EVERY rule: one firing case (injected series), one quiet case
   (healthy series). CI step runs promtool on every push — untested rule = red build (contract §2.2).
3. Rider (her call, Q-batch): Grafana dashboards-as-code provisioned in the metrics profile
   (money path, spine health, SLO buckets). No Alertmanager — pager stance unchanged (E16 candidate).
4. Evidence: promtool CI step green (run id); one intentionally-broken-rule CI run showing red
   (the bite proof, same class as E14's floors bite-proof).

### S3 — k6 money-path baseline (published number, consultative)

1. `scripts/load/k6-money-path.js`: create → idempotent replay → pay → confirm; VU ramp + steady
   (Q-batch: VUs/duration); thresholds aligned with the SLO buckets (100ms/250ms/1s).
2. One published run in `docs/load-test-baseline.md`: p95 per endpoint, throughput, error count,
   hardware + date + commit disclosed. Consultative — NOT a CI gate, no per-push job.
3. Evidence: k6 summary output verbatim in the doc; the baseline commit.

## STOP — BLOCK 1 AUDIT (channel). Fast turnaround per standing promise.

---

## BLOCK 2

### S4 — Scale restore drill (the "36 KB seed" answer)

1. Dispatch-only job `restore-drill-scale`: bulk-seed via SQL (target class 10k–100k journal lines /
   txns — Q-batch fixes the number so the job stays under ~15 min), plus a handful of API-created
   txns for realism → `backup.sh` → destroy → `restore.sh` → manifest counts + balance proof +
   one new API txn CONFIRMED post-restore.
2. `docs/drills/restore-scale-<date>.md`: volume, dump size, measured RTO at scale vs the 30-min
   budget, comparison line vs the 36 KB drill (honest delta), artifact-reality caveat kept.
3. Evidence: job run id + the RTO-at-scale line quoted in the PR body. Never extrapolate.

### S5 — PITR rehearsal (measured RPO, not a paragraph)

1. Local rehearsal harness: Postgres container with `archive_mode=on` + `archive_command` to a WAL
   dir → base backup (`pg_basebackup`) → transaction(s) → kill → restore replaying WAL to a target
   time (`recovery_target_time`) → validate counts + balance proof at the recovered state.
2. `docs/drills/pitr-<date>.md`: procedure, achieved-RPO window (measured, e.g. "recovered to
   within N s of target"), wall-clock, limits (host volume assumptions), link to §6 units.
3. Q-batch: CI-ification (dispatch job) or local-documented-only. Either is honest; the record
   says which.
4. Evidence: the drill doc + command transcript (text, never screenshots).

### S6 — DEBT-7 disposition (no silent carry-over)

1. Path A — **consolidate with proof**: unify the `postJournal` / `postJournalWithoutBalances`
   twins behind one guarded implementation; proof = full suite green, floors IDENTICAL
   (86.4/86.7/100/88.8/78.5), ladder ITs + property tests unchanged, and a PR note showing the
   E8/E9 guarantees re-evidenced (the reason the duplication was deliberate).
2. Path B — **close as won't-fix-by-design**: AGENTS §8 row → RESOLVED-WONTFIX with the original
   rationale on record (duplication as deliberate guarantee isolation) + why consolidation adds
   risk without behavior gain. Equally acceptable; silence is not.
3. Q-batch: her path proposal with tree evidence first.
4. Evidence: either the floors-identical run pair or the resolved row diff.

### S7 — Docs sweep + flip + citation (silence after)

1. Truth sweep: threat model (controls now in-app), README (operations features real: demo overlay,
   alerts, load number, scale restore, PITR), runbook §4 wording, testing-playbook §6 list if the
   new ITs enter the pre-release set.
2. CHANGELOG `[Unreleased]` consolidated across S0–S6.
3. Flip: epics E15 → ✅ (run pairs) — LAST content commit. Citation: exactly ONE commit after.
   Nothing after. Tag v1.1.0 = owner call post-epic, not here.
4. Evidence: verbatim chain + run list; digest-free (no release artifacts in this epic).
