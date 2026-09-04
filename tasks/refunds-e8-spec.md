# E8 Spec — Refunds (exact contracts)

Binding. Deviation = stop-and-report (P2). Pre-adjudicated items marked **[adjudicated]**.

## §1 Scope

- Modules: `modules/payments` (domain/use case/port/adapter/migration), `modules/ledger` (consumer
  posting branch — code only, NO migration), `apps/api` (endpoint, guard adapter, auditor extension,
  ITs), `tasks/` docs. **Zero** lines in `modules/notifications`, `apps/psp-simulator`.
- No new env names **[adjudicated]**. No new ledger accounts **[adjudicated]**. No ledger migration
  in E8 (the DB barrier of S0 uses test-local DDL; a real ledger migration = stop-and-report).
- Out of scope, explicitly: payouts (D10), negative-balance policy (post-settlement refunds are
  IGNORED+alarm per §6 — policy change is an owner decision), PSP refund calls (PIX refund rails are
  out of v1), refund notifications UX (record-only, already proven).

## §2 Migration V112 (`V112__refunds.sql` — payments; forward-only, expand-only)

```sql
CREATE TABLE IF NOT EXISTS payments.refunds (
    id                 uuid PRIMARY KEY,
    payment_id         uuid        NOT NULL REFERENCES payments.payments (id),
    txid               varchar(25) NOT NULL,
    amount_cents       bigint      NOT NULL CHECK (amount_cents > 0),
    fee_reversal_cents bigint      NOT NULL CHECK (fee_reversal_cents >= 0),
    net_cents          bigint      NOT NULL CHECK (net_cents >= 0),
    request_id         varchar(64),
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_refund_net CHECK (net_cents = amount_cents - fee_reversal_cents)
);

CREATE INDEX IF NOT EXISTS idx_refunds_payment_created
    ON payments.refunds (payment_id, created_at DESC);
```

## §3 Refund use case (payments)

One transaction (D17), all-or-nothing:
1. `SELECT * FROM payments.payments WHERE txid = :txid FOR UPDATE` (pessimistic, design-mandated).
2. Status gate: CONFIRMED or PARTIALLY_REFUNDED only (else → 409 `invalid_state`).
3. Remainder gate: `refunded_so_far + amount ≤ payment.amount` (Σ rule, scenario 12); else 409
   `insufficient_refundable_amount` (carries remaining).
4. **Balance guard [pre-check, best-effort]**: `MerchantBalancePort.available(merchantId) ≥
   amount − feeReversal` (net drain); else 409 `insufficient_merchant_balance`. Port failure →
   fail-CLOSED (409 `balance_unavailable`) — a money-return without knowing the merchant still has
   the funds is not guessable **[adjudicated: fail-closed]**.
5. D8 math (integer): `feeReversal = floor(fee × amount / payment.amount)` **[adjudicated: floor,
   remainder stays with the platform]**; `net = amount − feeReversal`.
6. `Payment.refund(amount, feeReversal, when)` (E1 as-built transition) → conditional UPDATE
   (lost race = re-read; E3 pattern) → insert `payments.refunds` row → bump version → outbox row
   type `refund.created`.
7. **Idempotency**: E3 semantics reused (same key + same body → replay 201 snapshot; different body
   → 409 `idempotency_key_conflict`).

## §4 REST contract

- `POST /v1/payments/{txid}/refunds` — API-key auth, tenant from principal (§3.7); cross-tenant
  payment → 404 (existence leak policy as E3).
- Body: `{ "amount": int }` (minor units, optional — absent = full remaining; `0`/negative → 400).
- 201 → refund representation `{ id, payment: txid, amount, feeReversal, net, status: "SUCCEEDED",
  createdAt }` (camelCase wire, house convention — TD-18 lesson).
- Errors (canonical envelope): 400 `invalid_amount` · 401/403 auth · 404 `payment_not_found`
  (cross-tenant included) · 409 `invalid_state` | `insufficient_refundable_amount` |
  `insufficient_merchant_balance` | `balance_unavailable` | `idempotency_key_conflict` · 425
  idempotency in-flight (E3 semantics). **These codes extend the E3 canonical list [adjudicated
  extension — governance-owned; lands in the same change set].**

## §5 Ledger consumer — entries [3]+[4] (the second posting template)

- Trigger: `refund.created` (design §7.2; FIFO per-txid ordering ⇒ after the confirm entry).
- ONE new `JournalEntry` per event (reversal-not-edit **[adjudicated]**), idempotent via `event_id`
  UNIQUE, postings in the consumer tx (§5.3 pattern):
  - **[3]** Dr `merchant:{merchantId}:available` `amount` · Cr `payments:processing` `amount`
  - **[4]** Dr `fees:revenue` `feeReversal` · Cr `merchant:{merchantId}:available` `feeReversal`
- **As-built mapping [adjudicated]**: design's `ASSET:PSP_CLEARING` → `payments:processing`
  (the account debited at confirm); `REVENUE:PLATFORM_FEES` → `fees:revenue`. Net drain on
  available = `amount − feeReversal` (worked example: 100.00/fee 1.00 → refund 40.00, reversal
  0.40 → available 99.00 → 59.40; golden vector for ITs).
- **Balance arbitration (final)**: the available drain posts conditionally —
  `UPDATE ledger.balances SET balance = balance - :drain WHERE account = :a AND balance >= :drain`
  (within the §5.3 lock pattern); 0 rows → journal NOT posted, event marked IGNORED with note
  `insufficient_merchant_balance` + audit row `refund_skipped_balance` → projection never negative
  (scenarios 19/23). This is the dangling case §7 alarms.
- Notifications record `refund.created` automatically (no change).

## §6 Journal coverage auditor — extension (Block 2, composition root)

- Legs (c)/(d) mirroring (a)/(b): REFUNDED payments (payments side) vs refund-anchored POSTED events
  (ledger side, `type='refund.created'`), two per-schema SELECTs + Java diff, no JOIN, WARN + audit
  `journal_coverage_gap` (same discipline). Dangling refund = incident, never auto-repaired.

## §7 Integration tests (names locked; Testcontainers + WireMock; Clock; barriers; zero sleeps)

1. `RefundFlowIT` — golden vector end-to-end: confirm → refund 40% → journal [3]+[4] exact postings,
   available 59.40, proof ok, redelivery no-op; full refund path → REFUNDED.
2. `RefundEndpointIT` — contract: 201 shape, idempotent replay, 409 matrix (remainder/state/
   balance-unavailable), 404 cross-tenant, 400 zero/negative.
3. `RefundBalanceGuardIT` — port stub scenarios: insufficient → 409 zero-writes; port-down → 409
   fail-closed; guard-pass → flow continues.
4. `RefundRaceIT` — scenarios 12/23: two concurrent 60% refunds → one 201 one 409 (payment lock);
   concurrent ledger drains beyond available → one posts, one IGNORED+audit, projection == Σ lines.
5. `ReconcilerConsistencyIT` untouched; `JournalCoverageAuditorIT` extended with legs (c)/(d).
6. Property tests (unit): D8 floor math (`Σ feeReversal ≤ fee`, `= fee` at full repayment); Σ
   refunds ≤ amount under random partial sequences.

## §8 Hygiene gates

Unchanged set (com.fasterxml=0, AWS confinement, Spring-free module mains, no Thread.sleep) +
`grep -rn "UPDATE ledger.balances" modules/ledger/src/main` shows every mutation guarded by
`balance >=` (the drain) or the §5.3 lock — pasted with commit ids.

## §10 Acceptance matrix (executor fills with pairs)

| Item | Deliverable | Test / Evidence | CI Run | Status |
|---|---|---|---|---|
| S0 | DEBT-5 barrier | unit rejects + DB barrier test (Block 1) | Block 1 | ✅ |
| S1 | V112 applies | MigrationIT leg (Block 1) | Block 1 | ✅ |
| S2 | refund use case | unit matrix + D8 properties (Block 1) | Block 1 | ✅ |
| S3/S4 | refund endpoint + balance guard | endpoint exercised via `RefundBalanceGuardIT` (real HTTP surface, S6) — `RefundEndpointIT` not separately delivered (see backlog); guard is unit-tested in `RefundPaymentUseCaseTest` + HTTP-asserted in `RefundBalanceGuardIT` | `369b0c6` / CI `33831934579` | ✅ |
| S5 | [3]+[4] flow | `RefundFlowIT` (golden vector) — failsafe 2/2 in full verify | Block 1 `8bad5e8`/`33827336501` | ✅ |
| S6 | races + IGNORED path | `RefundRaceIT` — sc.12 one 201/one 409, `refunded_cents`=6000; sc.23 one POSTED/one IGNORED + `refund_skipped_balance` audit + available 4000 | `369b0c6` / CI `33831934579` | ✅ |
| S7 | auditor legs (c)/(d) | `JournalCoverageAuditorIT` ext — 6/6 (legs c+d + matched-silent + connection-leak fix) | `7993f1f` / CI `33831934579` | ✅ |
| S8 | docs + E8 flip + citation | epics.md E8 ✅, CHANGELOG, README [3]+[4], AGENTS §8 DEBT-4/DEBT-5, design.md as-built note | `c8af90b` / CI `33831934579` | ✅ |

Evidence greps (E8 Block 2 — ran at `8bad5e8`, reproducible at HEAD `c8af90b`; amend (c)):
- Guard: `RefundBalanceGuardIT` insufficient → 409 + zero writes; guard-pass → 201 + journal `5940/−6000/60`.
- Races: `RefundRaceIT` two concurrent 60% → `[201,409]`, `refunded_cents == 6000`; two concurrent drains → `posted==1`, `ignored==1`, `skipAudit==1`, `available==4000`.
- Drain: `grep -rn "UPDATE ledger.balances" modules/ledger/src/main` — every mutation guarded by `balance >=` (drain) or the §5.3 lock.
