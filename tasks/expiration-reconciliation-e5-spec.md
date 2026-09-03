# E5 Spec — Expiration, Resurrection & Reconciliation (exact contracts)

Binding. Deviation = stop-and-report (P2). Pre-adjudicated seed decisions are marked **[seed]**.

## §1 Scope

- Modules touched: `modules/payments` (domain/ports/use cases/adapters), `apps/api` (scheduler +
  reconciler + auditor wiring, ITs), `tasks/` docs. **Zero** lines in `modules/ledger`,
  `modules/notifications`, `apps/psp-simulator` prod sources (ledger/notifications already consume
  unknown event types correctly — proven).
- No REST endpoints added. No new queues/topics. No delivery logic (E9).

## §2 Migration V111 (`V111__e5_reconciliation.sql`, forward-only, expand-only)

> **Disclosure (owner decision 2026-09-02):** the migration is **V111**, not the "V109 next free
> slot" originally seeded. At execution time the migration dir has **no V109**, and **V110 exists**
> (`V110__make_actor_key_id_nullable.sql`); V107 is the historical gap (never reused). V111 is the
> next free number. The executing block-1 prompt was amended V109→V111 accordingly.

```sql
ALTER TABLE payments.payments
    ADD COLUMN next_reconcile_at  timestamptz NULL,
    ADD COLUMN reconcile_attempts int NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_payments_pending_expires
    ON payments.payments (expires_at) WHERE status = 'PENDING';
```

Landed migrations are never edited. If V111 is taken at execution time, take the next free number
and disclose. **[seed: V111]**

## §3 Expiration scheduler

- Scan predicate: `status = 'PENDING' AND expires_at < now()` (partial index §2).
- Tick: `@Scheduled` fixed-delay `DARGENT_EXPIRATION_INTERVAL_MS`, gate `DARGENT_EXPIRATION_ENABLED`
  (default **false**), single-threaded, NOT transactional at the scheduled method **[seed]**.
- Per payment (own tx, `TransactionTemplate`, chunk ≤ `DARGENT_EXPIRATION_BATCH`):
  1. Conditional `UPDATE payments SET status='EXPIRED' WHERE id=:id AND status='PENDING' AND
     expires_at < now()` — 0 rows = webhook/confirm won the race → skip, zero writes.
  2. Outbox row: type `payment.expired`, version 1, aggregate_id = txid, envelope payload
     `{"txid":"…","expiresAt":"…","amountCents":N}` (envelope = shared serializer shape).
  3. Audit row: `command_name='expire_payment'`, `actor_key_id=NULL` (V110), merchant_id, txid.
- Loop until no more due rows in the tick. **[seed S3]**

## §4 Reconciler

- `PspPort.getCob(txid)` — new port method; adapter GET `/cobs/{txid}` (E2 truth endpoint; WireMock
  in ITs). Response mapping follows the E2 contract fields (status + paid amount + paidAt).
- Engine (payments module, Spring-free use case), driven by `apps/api` fixed-delay
  (`DARGENT_RECONCILER_SCAN_MS`, gate `DARGENT_RECONCILER_ENABLED` default **false**), runOnce-style:
  scan `status='PENDING' AND next_reconcile_at <= now()` → per payment (own tx):
  - PSP **PAID** → confirm/resurrect path (§5). Confirm computes fee (100 bps) exactly like E4.
  - PSP **EXPIRED** → local conditional expire exactly as §3 (audit `expire_payment`).
  - PSP still **ACTIVE/PENDING** → ladder: `next_reconcile_at = now + backoff[min(attempts, cap)]`,
    `reconcile_attempts = reconcile_attempts + 1`.
- **Backoff ladder [seed]:** `DARGENT_RECONCILER_BACKOFF_MS` default `60000,300000,900000,3600000`
  (1 m → 5 m → **15 m RPO anchor** → 1 h cap). Not E9's delivery numbers.
- **Give-up window [seed, size = spec decision]:** when `now() > expires_at +
  DARGENT_RECONCILER_GIVE_UP_HOURS` (default **72**): conditional clear
  `next_reconcile_at = NULL` (stops scheduling), audit `reconciliation_window_expired`. Manual
  review territory. The PENDING row stays PENDING — no fake terminal state.
- **No ShedLock, no advisory lock [seed]:** every transition is conditional; a blue-green duplicate
  scheduler loses races and no-ops. Revisit only with observed duplicate PSP polling (register first).

### §4.1 Environment contract (new names — complete list; defaults are contract)

| Name | Default | Meaning |
|---|---|---|
| `DARGENT_EXPIRATION_ENABLED` | `false` | expiration scheduler on/off |
| `DARGENT_EXPIRATION_INTERVAL_MS` | `60000` | scan fixed-delay |
| `DARGENT_EXPIRATION_BATCH` | `100` | max payments per tick |
| `DARGENT_RECONCILER_ENABLED` | `false` | reconciler on/off |
| `DARGENT_RECONCILER_SCAN_MS` | `60000` | scan fixed-delay **[seed: 60 s]** |
| `DARGENT_RECONCILER_BACKOFF_MS` | `60000,300000,900000,3600000` | per-payment ladder **[seed]** |
| `DARGENT_RECONCILER_GIVE_UP_HOURS` | `72` | resurrection window after `expires_at` |
| `DARGENT_JOURNAL_COVERAGE_ENABLED` | `false` | coverage auditor on/off |
| `DARGENT_JOURNAL_COVERAGE_SCAN_MS` | `300000` | coverage scan fixed-delay |

## §5 Confirm / resurrection (shared path, exactly once)

- From **PENDING**: conditional `PENDING→CONFIRMED` (fee, `late=false`), audit
  `confirm_from_reconciliation` (actor NULL), outbox `payment.confirmed` `{amount, fee, net,
  late:false}` — E4 §5.3 payload shape.
- From **EXPIRED** (resurrection): conditional `EXPIRED→CONFIRMED`, same effects with `late=true`
  in the envelope payload **[seed S4]**. One audit row per originating command; no separate
  "resurrect" concept **[seed]**.
- Loser of any race re-reads → already CONFIRMED → no-op, zero writes.
- PSP says PAID with **amount mismatch** → do NOT confirm; audit `reconciliation_amount_mismatch`
  + leave scheduled (incident territory). (Defensive; E2 payer bank pays full.)

## §6 Journal coverage auditor (DEBT-4) — composition root only

- Lives in `apps/api` (the only layer that may see both schemas), gated + scan interval per §4.1.
- Gap queries: (a) `payments.payments` CONFIRMED with no `ledger.events` row `status='POSTED'` for
  its txid-anchored event; (b) `ledger.events` POSTED `payment.confirmed` with no CONFIRMED payment.
  Gap → WARN log + payments audit row `command_name='journal_coverage_gap'` (details in aggregate_id
  / request_id columns — no schema change). **Detect-and-alarm only; never auto-repairs.**
- Seed note: exact join keys are defined at implementation from the as-built `ledger.events` columns
  (`txid` anchor); if the join is ambiguous, STOP-and-report with the two candidate queries.

## §7 Integration tests (names locked; Testcontainers + WireMock; injected Clock; zero sleeps)

In `apps/api/src/test/java/io/dargent/api/payments/`:

1. `ExpirationSchedulerIT` — due → EXPIRED + outbox `payment.expired` + audit; not-due untouched;
   confirm-won race no-ops.
2. `ReconcilerConfirmIT` — **scenario 26**: create → webhook suppressed (no intake) → cob PAID →
   `reconcileOnce()` → CONFIRMED `late=false`, audit, outbox; idempotent second run.
3. `ReconcilerResurrectionIT` — **scenarios 11 + 27**: local EXPIRED (time travel) + PSP PAID →
   resurrect `late=true` exactly once under concurrent confirm (barrier); late webhook beyond
   anti-replay window rejected AND reconciler confirms.
4. `ReconcilerGiveUpIt` — past window: no confirm, `reconciliation_window_expired`, unscheduled.
5. `JournalCoverageAuditorIT` — simulated dangling payment → WARN + audit; clean → silent.
6. Scenario 9/10 legs (late/unknown event → IGNORED consistent; payload_raw replay → same result)
   live inside 2/3 or a small dedicated IT.

## §8 Hygiene gates (pasted with commit ids)

- AWS SDK still confined to messaging adapters (reconciler uses the PSP HTTP port, not AWS).
- `Thread.sleep` = 0 in touched tests; `@Disabled` = forbidden; scheduler mains Spring-free;
  conditional UPDATEs only (no `UPDATE payments SET status=...` without `WHERE status=`).

## §10 Acceptance matrix (skeleton — executor fills with pairs)

| Item | Deliverable | Test / Evidence | CI Run | Status |
|---|---|---|---|---|
| S0 | DEBT-1 rejecting-contract test | `PaymentTest.restore_rejects_*` (3 rejecting tests + legal round-trip; module unit tests)` | `242b6e3` / `33693408878` | ✅ |
| S1 | V111 reconciliation columns + pending-expires index used | `V111__e5_reconciliation.sql` applied; `ExpirationSchedulerIT` + reconciler harness exercise the index | `46fad68` / `33694469538` | ✅ |
| S2 | expiration contract | `ExpirationSchedulerIT` | `a7f626e` / `33700561182` | ✅ |
| S3 | reconciler confirms (sc.26) | `ReconcilerConfirmIT` | `48ade01` / `33706674658` | ✅ |
| S4 | resurrection exactly-once (sc.11/27) | `ReconcilerResurrectionIT` | `8128eb5` / `33707174938` | ✅ |
| S5 | give-up window | `ReconcilerGiveUpIT` (renamed from the spec's `ReconcilerGiveUpIt` — failsafe include is case-sensitive `**/*IT.java`) | `b274493` / `33709904795` | ✅ |
| S6 | scenarios 9/10 legs | `ReconcilerConsistencyIT` | `59a0eda` / `33710432248` | ✅ |
| S7 | DEBT-4 coverage auditor | `JournalCoverageAuditorIT` | `470e10c` / `33711320405` | ✅ |
| S8 | docs + E5 row flip + citation | `README.md` recast (lines 23/74/75 live), `docs/epics.md` E5 → ✅, CHANGELOG Unreleased | (this commit) / citation run | ✅ |

> **§10 amendment notes (2026-09-02, executor):** S1's "V109 applies" is superseded by as-built V111
> (`46fad68`, see §4 numbering note). S5's spec-text `ReconcilerGiveUpIt` (lowercase-It) diverges from the
> landed `ReconcilerGiveUpIT` — the root `pom.xml` failsafe include is case-sensitive `**/*IT.java`, so a
> lowercase `...It` surname would be silently skipped; the class was renamed to house-style and re-verified
> green (run `33709904795`). Both are doc-level corrections; no spec behavior change.
