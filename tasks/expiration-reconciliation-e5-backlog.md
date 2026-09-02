# E5 Backlog — Expiration, Resurrection & Reconciliation (opens M3)

Molde: Spotpobre P0. Story map in execution order; acceptance per story. E5 flips its own epic row
only (M3 waits for E8+E9).

## Story map

```
E5 Expiration · Resurrection · Reconciliation
├── S0  Rider: DEBT-1 rejecting-contract test on Payment.restore()   [Block 1, step 0]
├── S1  Migration V111 (next_reconcile_at, reconcile_attempts, partial index) [Block 1]
├── S2  Expiration scheduler (PENDING→EXPIRED, outbox, audit)        [Block 1]
├── S3  PSP truth: PspPort.getCob(txid) + reconciler engine          [Block 1]
├── S4  Resurrection (EXPIRED→CONFIRMED late=true, exactly once)      [Block 1]
├── S5  Give-up window + ReconcilerGiveUpIT                           [Block 2]
├── S6  Late/replay robustness legs (scenarios 9, 10)                 [Block 2]
├── S7  Journal coverage auditor (DEBT-4) + IT                        [Block 2]
└── S8  Docs honesty pass + matrix + epic flip                        [Block 2]
```

## Stories

### S0 — Rider DEBT-1
- `Payment.restore()` (persistence hydration) previously trusted snapshots. Closed 2026-09-02: an
  owner-adjudicated (stop-and-report) domain-side fix — `validateRestored()` re-imposes the
  create()/confirm() invariants at the hydration seam (positive BRL amount, expiresAt after createdAt,
  non-negative refunded, status-gated fee/net/confirmedAt for the confirmed family).
- A rejecting-contract test proves a corrupted snapshot (invariant-violating state) through the
  restore/hydration seam → exception, never a silent invalid aggregate; a round-trip guard proves all
  six legal lifecycle states still hydrate.
- **Accept:** test green in CI; DEBT-1 closes on audit.

### S1 — Migration V111 (forward-only, expand-only)
- `ALTER TABLE payments.payments ADD COLUMN next_reconcile_at timestamptz NULL,
  ADD COLUMN reconcile_attempts int NOT NULL DEFAULT 0;`
- Partial index for the expiration scan: `CREATE INDEX idx_payments_pending_expires ON
  payments.payments (expires_at) WHERE status = 'PENDING';`
- **Accept:** applies clean on real PG in IT harness; index used by the scan predicate.

### S2 — Expiration scheduler
- `apps/api` config, `@Scheduled` fixed-delay (`DARGENT_EXPIRATION_INTERVAL_MS`), gated
  (`DARGENT_EXPIRATION_ENABLED`, default false), single-threaded; NO `@Transactional` on the scheduled
  method — per-payment tx via `TransactionTemplate` (chunk ≤ `DARGENT_EXPIRATION_BATCH`).
- Per due payment: conditional UPDATE `PENDING→EXPIRED WHERE expires_at < now()` → lost race =
  webhook won = fine → `payment.expired` outbox row + audit `expire_payment` (actor NULL per V110).
- Domain: pure expiration logic unit-tested first (Clock-injected); scheduler is thin.
- **Accept:** `ExpirationSchedulerIT` green (due expires w/ outbox+audit; not-due untouched;
  confirm-won-race no-ops).

### S3 — PSP truth + reconciler engine
- `PspPort.getCob(txid)` (adapter: GET `/cobs/{txid}` — the E2 truth endpoint; WireMock in ITs).
- Reconciler use case (module, Spring-free): scan due (`status='PENDING' AND next_reconcile_at <= now`),
  per payment: PAID → confirm path (S4); EXPIRED at PSP → local conditional expire (as S2);
  ACTIVE/PENDING → schedule next poll via backoff ladder (env list; RPO anchor 15 m).
- State per payment: `reconcile_attempts++`, `next_reconcile_at = now + ladder[min(attempts, cap)]`.
- **Accept:** `ReconcilerConfirmIT` green (scenario 26: webhook suppressed → reconciler confirms,
  `late=false`, audit `confirm_from_reconciliation`, outbox `payment.confirmed`).

### S4 — Resurrection (exactly once)
- PSP says PAID, local row EXPIRED (or expires mid-flight): conditional `EXPIRED→CONFIRMED` wins once;
  loser re-reads → CONFIRMED → no-op. `late=true` in the `payment.confirmed` envelope payload
  (E4 §5.3 shape). Audit `confirm_from_reconciliation` (actor NULL). Fee computed at confirm (100 bps).
- **Accept:** `ReconcilerResurrectionIT` green (scenario 11 race: exactly one winner; scenario 27:
  late webhook beyond anti-replay window rejected AND reconciler confirms).

### S5 — Give-up window (Block 2)
- Past `expires_at + DARGENT_RECONCILER_GIVE_UP_HOURS`: stop scheduling (`next_reconcile_at = NULL`),
  audit `reconciliation_window_expired` — manual review territory, never silent forever-polling.
- **Accept:** `ReconcilerGiveUpIT` green.

### S6 — Late/replay robustness legs (scenarios 9, 10 — Block 2)
- Scenario 9: out-of-order/late event → final state consistent, `IGNORED` persisted (webhook intake
  already ignores unknown; the late-arrival path lands in confirm-path tests at use-case altitude).
- Scenario 10: replaying `payload_raw` produces the same result (idempotent confirm; zero double
  side effects).
- **Accept:** legs green inside `ReconcilerResurrectionIT` or a dedicated small IT.

### S7 — Journal coverage auditor (DEBT-4 — Block 2)
- Composition-root glue in `apps/api` (both schemas visible at app level; module isolation intact):
  scheduled scan (`DARGENT_JOURNAL_COVERAGE_*`) for CONFIRMED payments with no POSTED `ledger.events`
  row AND ledger POSTED events with no CONFIRMED payment. Gap → WARN log + audit
  `journal_coverage_gap` (both directions). **Never auto-repairs** — dangling = incident.
- **Accept:** `JournalCoverageAuditorIT` green (simulated gap flagged; clean state silent).

### S8 — Docs + flip (Block 2)
- README: the TD-15-recast sentence ("future reconciliation job (E5, not started)") flips to present
  tense ONLY when the proof lands; money-flow "reconciler (E5, future)" → reconciler (live).
- CHANGELOG; `docs/epics.md` E5 row ✅ (same change set, epics.md conventions); matrix §10 filled.
- **Accept:** flip = last content commit + exactly one citation commit; citation run unregistered
  (#57/#67 precedent). **M3 does NOT flip** (waits E8/E9).
