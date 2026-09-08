# E8 Backlog — Refunds (opens the second half of M3)

Molde: Spotpobre P0. E8 flips its own row only; M3 waits for E9.

## Story map

```
E8 Refunds — partial/total · fee reversal (D8) · balance guard
├── S0  Rider: DEBT-5 barrier (ctor check + DB deferred trigger)      [Block 1, step 0]
├── S1  Migration V112: payments.refunds table                        [Block 1]
├── S2  Refund domain/use case (D17 lock, Σ guard, D8 fee reversal)   [Block 1]
├── S3  REST POST /v1/payments/{txid}/refunds (idempotency, errors)   [Block 1]
├── S4  Balance guard port (composition-root adapter, ledger read)    [Block 1]
├── S5  refund.created flow + RefundFlowIT (golden vector [3]+[4])    [Block 1]
├── S6  Ledger-side races + insufficient-balance IGNORED (sc.19/23)   [Block 2]
├── S7  DEBT-4 auditor extension: refund dangling legs                [Block 2]
└── S8  Docs + matrix + E8 row flip + citation                        [Block 2]
```

## Stories

### S0 — Rider DEBT-5 (the barrier before the traffic)
- `JournalEntry` ctor: refuse unbalanced postings (Σ debits ≠ Σ credits), < 2 postings, amounts ≤ 0 —
  mirror the "mechanically correct" rules; typed domain exception. DB barrier: deferred constraint or
  trigger on `ledger.postings` rejecting an unbalanced journal at commit (test-local DDL first; if it
  must be a real migration, that is a LEDGER migration → stop-and-report for adjudication, since E8
  is payments-led by plan).
- Unit tests: balanced 2/3-line entries pass; the 4th-analysis rejects (pad, skip-line, sign hack)
  fail. **Accept:** barrier green BEFORE any refund line exists; DEBT-5 closes on audit.

### S1 — Migration V112 (payments; forward-only, expand-only)
- `payments.refunds`: id (uuid PK), payment_id FK, refund_id/event anchor, amount_cents > 0,
  fee_reversal_cents ≥ 0, net_cents, status, request_id, created_at + index (payment_id, created_at).
- Exact DDL in spec §2. **Accept:** applies on real PG (MigrationIT leg).

### S2 — Refund use case (TDD on pure domain first)
- One transaction (D17): `SELECT FOR UPDATE` on the payment → validate remainder (`Σ refunds +
  this ≤ amount`) → D8 fee reversal math (proportional, integer-safe, property tests) →
  `Payment.refund(...)` transition (E1 as-built) → insert refunds row → bump version → outbox
  `refund.created`.
- **Accept:** unit matrix (partial, multiple partials, exact-remainder full, over-refund 409,
  zero/negative 400-domain, fee math property tests).

### S3 — REST endpoint
- `POST /v1/payments/{txid}/refunds`, body `{ "amount": int|null }` (null/absent = full remaining),
  `Idempotency-Key` semantics reused, API-key tenant from principal (§3.7 — a merchant refunds only
  its own payment; cross-tenant → 404). Canonical error envelope; new error codes per spec §4
  (adjudicated extension of the E3 contract).
- **Accept:** endpoint ITs (201 with refund representation + payment status; 409s; 401; idempotent
  replay; cross-tenant 404).

### S4 — Balance guard port (isolation-clean)
- Port in payments (`MerchantBalancePort.available(merchantId)`), adapter wired in `apps/api`
  reading the ledger's existing balance query — no module cross-import, no HTTP hop.
- Use case: guard runs inside the lock, BEFORE transition; insufficient → canonical 409, zero writes.
  Best-effort pre-check (the ledger's conditional drain remains final arbitration).
- **Accept:** unit tests with fake port (sufficient/insufficient/port-down → fail-closed 409? spec §4
  rules; port failure must NOT block refunds if ledger is down → spec decision §4, implemented as
  adjudicated).

### S5 — `refund.created` flow + golden-vector IT
- Envelope via shared serializer, version 1, payload `{amount, feeRefund, netRefund, refundId, txid}`.
- Ledger consumer branches on type: entries [3]+[4] in ONE journal (as-built account mapping, spec §5)
  in the same tx pattern as §5.3; notifications record (already proven for every type).
- `RefundFlowIT`: full flow with the design §4.x golden vector — 100.00 confirm (fee 1.00) → refund
  40.00 (fee reversal 0.40) → journal Dr available 40 / Cr processing 40 + Dr fees 0.40 / Cr
  available 0.40 → available 59.40 exactly; proof ok; second delivery no-ops.
- **Accept:** IT green; DEBT-5 barrier exercised by construction.

### S6 — Ledger races + insufficient balance (Block 2)
- Scenario 12/23: two concurrent refunds (60%/60%) → one 201 one 409 (payments lock); concurrent
  ledger drains beyond available → one posts, one conditional-0-rows → IGNORED + audit, projection
  intact (barrier-raced IT).
- Scenario 19: refund beyond available (e.g., post-settlement) → use-case guard rejects when
  visible; ledger-side IGNORED+audit when it slips through (deterministic forced IT).
- **Accept:** `RefundBalanceGuardIT` + race ITs green; projection == Σ lines after every storm.

### S7 — DEBT-4 auditor extension (Block 2)
- Coverage leg (c): REFUNDED payment with no refund journal; leg (d): refund journal with no REFUNDED
  payment. Same two-SELECTs + diff mechanics (no cross-schema JOIN), same audit + WARN discipline.
- **Accept:** `JournalCoverageAuditorIT` extended; AGENTS §8 DEBT-4 note updated in S8 docs.

### S8 — Docs + flip (Block 2)
- README guarantee table gains the refund line (present tense only when proven); money-flow adds
  [3]+[4]; CHANGELOG; AGENTS §8 (DEBT-5 closed; DEBT-4 note extended); design.md deltas (as-built
  account mapping note); epics.md E8 ✅ (same change set) — **M3 stays ◐**; matrix §10 filled.
- **Accept:** flip = last content commit + exactly one citation commit (run unregistered, #57/#67).
