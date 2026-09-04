# E9 Spec — Delivery Hardening (exact contracts)

Binding. Deviation = stop-and-report (P2). Pre-adjudicated items marked **[adjudicated]**.

## §1 Scope

- Modules: `modules/payments` (delivery use case + port/adapter), `apps/api` (endpoint + republish +
  wiring + ITs), `docs/` (recipes + design deltas), `tasks/` matrix. **Zero** lines in
  `modules/ledger`, `modules/notifications`, `apps/psp-simulator`.
- **Zero migrations [adjudicated target]**: V105 already carries `EXHAUSTED`, `attempt_count`,
  `next_attempt_at`. If a column is truly missing → stop-and-report.
- Out of scope: DLQ auto-redrive automation, new queues/topics, PSP delivery concerns, any UI.

## §2 Exhaustion contract

- On publish failure: `attempts = attemptCount + 1`; if `attempts < maxAttempts` → existing ladder
  (30s/2m/5m — FROZEN **[adjudicated]**); if `attempts >= maxAttempts` → conditional
  `UPDATE payments.outbox SET status='EXHAUSTED', attempt_count=:attempts WHERE id=:id AND
  status='PENDING'` (0 rows = lost race → re-read/no-op).
- `claimPending` never returns EXHAUSTED (status filter — already true; keep asserted by test).
- `maxAttempts` default **3** — meaning: initial try + 2 retries on the ladder, then EXHAUSTED.
- Poison-ish rows (writer bugs, invalid payload) keep today's leave-PENDING+log behavior — writer
  bugs are code bugs, not delivery failures; do NOT auto-exhaust them.

## §3 Requeue endpoint

- `POST /v1/outbox/{id}/requeue` — admin-gated (admin key scope via §4.1 env; if the key model has
  no scope concept, a dedicated admin key value — minimum honest guard; roles system = out).
- Semantics: conditional `UPDATE ... SET status='PENDING', attempt_count=0, next_attempt_at=now()
  WHERE id=:id AND status='EXHAUSTED'` → 1 row = 200 `{ id, status: "PENDING", attemptCount: 0 }`;
  0 rows + row exists non-EXHAUSTED → 409 `not_exhaustible`; unknown id → 404.
- Audit: `outbox_requeued`, actor = API-key principal (REAL actor), aggregate_id = outbox row id.
- Relay picks it up on the next poll (nothing else to do — this is the scenario 19 ending).

## §4 Republish tool

- `POST /v1/outbox/republish` body `{ "from": ISO-8601, "to": ISO-8601, "types": [string]? }`
  (window on `published_at`, `from <= published_at < to`, max window 30 days, max batch **500** —
  bounded; more → client narrows the window).
- For each SENT row matched: INSERT new PENDING row — new uuid id, **new event_id =
  `{original.eventId}-r{n}` where n = the row's replay ordinal (deterministic, dedupe-safe)**
  **[adjudicated: deterministic salted id — a re-run of the same republish produces the SAME
  new ids, so re-running the tool is itself idempotent at consumers]**, same type/payload/
  aggregate_id/request_id, `attempt_count=0`, `next_attempt_at=now()`.
- Originals untouched (status stays SENT). Response: `{ "matched": N, "republished": M }`
  (M < N only via lost races). Audit `outbox_republished` (window + M + actor; aggregate_id =
  window marker).
- Auth: admin-gated like §3.

### §4.1 Environment contract (new names — complete list; defaults are contract)

| Name | Default | Meaning |
|---|---|---|
| `DARGENT_RELAY_MAX_ATTEMPTS` | `3` | ladder runs before EXHAUSTED |
| `DARGENT_OUTBOX_ADMIN_KEY` | — | admin key value gating requeue/republish (required for the endpoints; absent = endpoints 404-hidden) |

No other new names. `DARGENT_RELAY_BATCH/POLL_MS/WORKERS/OUTBOX_RETENTION_DAYS` unchanged.

## §5 Errors & wire (house conventions)

- Canonical envelope; new codes: `409 not_exhaustible` · `400 invalid_window` (bad ISO, inverted,
  >30d) · `401/403` auth · `404 unknown id` (requeue) — camelCase wire (TD-18 standing).

## §6 Integration tests (names locked; Testcontainers + LocalStack; Clock; barriers; zero sleeps)

1. `OutboxExhaustionIT` — forced publisher failure ×3 → EXHAUSTED, unscheduled (second relay cycle
   claims nothing); ladder timings asserted via injected Clock, not sleeps.
2. `OutboxRequeueIT` — **scenario 19 end-to-end**: exhausted row → requeue (audited, real actor) →
   relay publishes → SENT; double-requeue → same single effect; requeue-of-PENDING → 409; auth
   negatives (no key, wrong key). **Q11 rotation-window 403 leg**: `OutboxAdminRotationIT` — env points at revoked predecessor, active successor presents → 403 (validation first); revoked predecessor presents → 401.
3. `OutboxRepublishIT` — window replay mints bounded new rows with salted ids; originals SENT;
   re-run of the same republish → consumers dedupe (zero second effects — **scenario 20** in
   ledger+notifications). **Notifications consumer dedupes by `eventId` (ON CONFLICT)** — re-runs of the same republish are idempotent at both consumers. Replay of first trip re-notifies (new UUID) — ratified limitation. Window bound enforced (400 >30d), empty window → 200/0.
4. Scenario 20 ledger leg (replay of 1st trip ratified): republished `payment.confirmed` consumed twice-equivalently → journal count unchanged, balances unchanged (extends the RefundFlow harness pattern). Proof via `OutboxRepublishIT.republish_re_run_produces_identical_new_ids` — re-run produces identical deterministic UUID v3 `{original}-r{n}` eventIds → consumer dedupe by `eventId`.

## §7 Hygiene gates

Standing set + `grep -rn "EXHAUSTED" modules/payments/src/main` shows only the exhaustion/requeue
paths (no new writers of the state); every outbox status mutation remains conditional — pasted with
commit ids.

### §7.1 S6 docs riders (owner-approved 2026-09-03 — 3rd external analysis triage)

- **TD-26**: README guarantee/stack tables + `slos.md` carry present-tense claims for M4/M5
  mechanisms ("shutdown-under-load gate in CI", "security gates in CI", `Micrometer + Prometheus`,
  canary, `dargent_*` as live SLI source). S6 annotates each with `(M4)`/`(M5)` — same honesty
  treatment as TD-22. Annotate, never delete.
- **Refund × PSP**: design.md + CHANGELOG gain one limitation line — refund is an internal fact +
  outbox event; PSP devolution rail is a post-v1.0.0 epic candidate.
- **DEBT-7 is out of E9 scope**: `postJournal` × `postJournalWithoutBalances` SQL duplication
  consolidates at the M4 refactor window (own IT evidence). Zero money-SQL edits in E9.

## §10 Acceptance matrix (skeleton — executor fills with pairs)

| Item | Deliverable | Test / Evidence | CI Run | Status |
|---|---|---|---|---|
| S1 | exhaustion contract | unit matrix + `OutboxExhaustionIT` | `d34c414` / #150 `33891162497` | ✅ |
| S2 | audited requeue | `OutboxRequeueIT` (sc.19 e2e) + `OutboxAdminRotationIT` (403 leg) | `8fcb2e1` / #150 `33891162497` | ✅ |
| S3 | republish tool | `OutboxRepublishIT` + `OutboxRepublishRotationIT` | `eb7c06d` / #150 `33891162497` | ✅ |
| S4 | sc.20 no-double-journal | `OutboxRepublishIT.republish_re_run_produces_identical_new_ids` (deterministic `{original}-r{n}` → consumer dedupe by `event_id`) | `eb7c06d` / #150 `33891162497` | ✅ |
| S5 | DLQ recipes | `docs/runbooks/dlq-inspection.md` + runnable queries | #150 `33891162497` | ✅ |
| S6 | docs + E9 ✅ + **M3 ✅** + citation | `design.md` §13/§15, `CHANGELOG.md` 1.0.3 | #151 `33893091359` | ✅ |
