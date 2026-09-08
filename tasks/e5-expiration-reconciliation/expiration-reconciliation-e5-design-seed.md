# E5 Design Seed — decisions taken before the spec exists

**Status:** input notes for the future E5 spec (`expiration-reconciliation-e5-*`). **E5 is blocked on E3R** —
the 2nd external audit (2026-08-29) reopened E3/E4 in substance; this seed records answers given on 2026-08-29
so the spec author does not re-derive them. Nothing here is binding until the E5 spec publishes it.

## S0 — Sequencing (question 1)

- **Nothing starts before E3R closes** (create path hotfix + POST /v1/payments + POST /webhooks/psp + evidence
  pass). The "E3+E4 done" premise in the planning questions is the audited defect; do not plan on top of it.
- After E3R: **E6 (outbox + messaging) before E5**, per the ledger's own priority rule (dependency first, then
  value = unlocks the most): E6 unlocks E7 → E8/E9 and E10; E5 unlocks nothing downstream. Milestones agree
  (E6 = M2, E5 = M3).
- **Sequential, not parallel:** both epics touch `modules/payments` and both may add Flyway migrations to the
  same module — parallel work risks V-version collisions and merge friction in the solo flow.
- Flip condition: if closing the money loop (expiration + late confirmation) becomes a demo/business priority,
  E5 can go first — it is unblocked the moment E3R is green. Nothing downstream waits on it either way.

## S1 — Reconciler: polling, not event-driven (question 2)

- **Scheduled polling job** — this is also what design already frames: E2's `GET /cobs/{txid}` exists as "the
  reconciler's truth endpoint"; the E5 brief says "reconciler polls GET /cob and confirms on its own".
- **DLQ-driven reconciliation is rejected as the primary mechanism** — it cannot see the failure case that
  matters: a webhook that never arrived leaves no event anywhere. The bus (E6) and its DLQs (E9) only carry
  events that existed. Polling the PSP is the only way to detect "PSP says paid, we are still PENDING/EXPIRED".
- Flow: scan due payments → per payment, `GET /cobs/{txid}` → paid → confirm (conditional UPDATE,
  `late=true` when EXPIRED) → expired at PSP → expire locally → still pending → schedule next poll.
- Double-run safety (blue/green): no ShedLock (rejected); every transition is a conditional UPDATE (AGENTS 3.2)
  so concurrent schedulers are safe by arbitration — one wins, the loser re-reads and no-ops. If duplicated PSP
  polling ever matters, a Postgres advisory lock is the in-DB answer (no new infra) — spec decision.

## S2 — Reconciler schedule (question 3)

- **Fixed scan interval (60 s fixed-delay) + per-payment poll backoff**, aligned with design's RPO ≤ 15 min:
  poll schedule per payment ≈ **1 m → 5 m → 15 m (RPO anchor) → 1 h cap**; stop at `expires_at` + resurrection
  window (window length is a spec decision; past it → manual review flag, not silent forever-polling).
- Per-payment throttling needs state: **V111 adds `next_reconcile_at` (nullable) to `payments`** (E5 owns its
  migrations; derive-from-`updated_at` does not work — polls do not transition the payment, so the age never
  advances). Index choice per design §5.1; numbers become named constants/env (env names are contract).

## S3 — Expiration scheduler (question 4)

- **`@Scheduled` fixed-delay, single-threaded trigger — and NO `@Transactional` on the scheduled method.**
  A tx spanning the whole scan is the defect; the unit of transaction is **per payment**, short, via
  `TransactionTemplate` (same pattern as E3R's use case).
- Each tick: page due rows (`status='PENDING' AND expires_at < now` — verify/extend the design §5.1 partial
  index for this predicate) → chunk (≤ 100) → per payment: conditional UPDATE to `EXPIRED` (lost race = the
  webhook confirmed it first → fine) + `PaymentExpired` outbox row + audit row. Loop until none due.
- Injected `Clock` everywhere (time travel tests); EXPIRED stays non-terminal-resurrectable (E1 behavior).

## S4 — Resurrection audit (question 5)

- Yes to `audit_log` — but **no separate "resurrect" concept**: resurrection is the `EXPIRED→CONFIRMED`
  transition carrying `late=true`. One audit row per originating command:
  `expire_payment` (scheduler; actor null) · `confirm_from_reconciliation` (reconciler; actor null) ·
  `confirm_from_webhook` (exists, E4).
- `late=true` is carried by the `payment.confirmed` envelope payload (already specified, E4 §5.3 step 6);
  `audit_log` has no payload column. If `late` must be queryable in audit, V111 may add a nullable
  `details jsonb` — spec decision, not assumed.

## Anchors for the spec author

- Playbook scenarios 9–11, 26–27 (11 = expiry-vs-confirm race; 26–27 = reconciler) + full-loop: create →
  no webhook → reconciler confirms.
- E9's backoff numbers (30 s→2 m→5 m, redrive 5) are **delivery** hardening — do not reuse for reconciler polls.
- Consumer/delivery idempotency and outbox mechanics are E6/E9's; E5 only writes rows.
- Scenarios must run in CI (AGENTS §5.6 once E3R lands); disabled tests = debt (§5.5).
