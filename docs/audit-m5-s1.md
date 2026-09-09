# M5 S1 diff audit — Card as second rail (PR #B)

Audit of the S1 card-rail implementation (2026-09-10): card is added as a second
`PaymentRail` strategy **with zero domain edits**. The diff extends PspPort (contract +
transport), adds the card adapter and tests, fixes a latent rail-seam persistence bug
exposed by the second rail, and wires the new beans. Reference for citation:
`<diff-audit-ref> = docs/audit-m5-s1.md` at the commit this document ships in.

## 1. Domain — zero edits (hard)

`git diff --name-only HEAD -- modules/payments/src/main/java/io/dargent/payments/domain/` → **EMPTY**.
No existing domain file was modified. Two files were added in S0 (`PaymentRail.java`,
`RailAssignmentPort.java`) and remain unchanged here. No new domain files added in S1.

## 2. PspPort contract change (additive, disclosed)

`domain/port/out/PspPort.java` — two additive changes:

| Change | Rationale |
|---|---|
| `CreateChargeInput` gains `String cardToken` (nullable, null = PIX) + new 5-arg public convenience ctor (delegates to 6-arg with `cardToken=null`). | Transport-level: the card adapter needs `cardToken` passed to the PSP endpoint. The field is nullable (PIX never sets it) so the existing 5-arg call sites compile unchanged. The public ctor was package-private before; made public to allow the card IT to construct `CreateChargeInput` directly. |
| No other PspPort record or method changed. | `ChargeResult`, `CobStatus`, `CobState` shape is unchanged — the card adapter's 201/409/402 all map into existing constructors. |

**Domain impact:** `CreateChargeInput` lives in `domain/port/out` (the port boundary, not the
aggregate model). The cardToken addition is a transport field; it does not enter the
aggregate and does not alter any invariant. The existing 5-arg ctor delegates exactly as before.

## 3. Card adapter (additions only)

| File | Purpose |
|---|---|
| `adapter/out/psp/CardChargeAdapter.java` | PSP adapter for E2 simulator instant card profile. Implements `PaymentRail` (not PspPort). POST `/card-charges` → 201 PAID / 402 card_declined / 409 read-back. GET `/card-charges/{txid}` for reconciler. `presentment()` returns null (FINDING-S1-2). `rail()` = `"card"`. Same retry posture as `SimulatorChargeAdapter`. |
| `application/PspDeclinedException.java` | Maps 402 to a checked exception handled by GlobalExceptionHandler → `ErrorCode.CARD_DECLINED` (402 + `payment_required`). |

## 4. Wiring — apps/api

| File | Change |
|---|---|
| `config/PaymentsCompositionConfig.java` | Adds `cardRail` bean (`CardChargeAdapter` from same base-url); `createPaymentUseCase` and `reconciliationUseCase` now receive `Map.of("pix", pixRail, "card", cardRail)`. `PaymentController` receives `List<PaymentRail>` → keyed by `rail()` (not bean name, which is a Spring artifact). |
| `controller/PaymentController.java` | `railsByRail = rails.stream().collect(toMap(PaymentRail::rail, r->r, (a,b)->a, LinkedHashMap::new))`; `presentmentFor(txid)` uses `getOrDefault(railOf, pixRail)` — the controller's presentment logic remains rail-agnostic. |
| `error/ErrorCode.java` | Adds `CARD_DECLINED("card_declined", PAYMENT_REQUIRED, "Card declined by the PSP")`. |
| `error/GlobalExceptionHandler.java` | Adds `@ExceptionHandler(PspDeclinedException.class)` → maps to `CARD_DECLINED`. |

## 5. Rail-seam flush fix (latent S0 bug, exposed by S1)

**FINDING-S1-3:** `PaymentJpaAdapter.save` used `em.persist(entity)` without flush. The
`JdbcRailAssignmentPort.assign` call in the same `txTemplate` executed a raw JDBC
`UPDATE ... SET rail = ? WHERE txid = ?` on the un-flushed row — the UPDATE matched 0
rows because Hibernate had not yet issued the INSERT. The `payments.payments.rail` column
kept the DDL default `'pix'`. S0 never noticed because the default equaled the PIX rail
name; S1 card set `'card'` → exposed the silent no-op.

**Fix:** `PaymentJpaAdapter.save` now calls `em.flush()` immediately after `em.persist()`.
This forces the INSERT into the database within the same transaction, making the row
visible to same-transaction raw JDBC. The flush is confined to the adapter layer; no
domain or application code changes.

**Incident evidence:** before the fix, `assign(txid, "card")` executed `exists=0 updated=0`.
After the fix, `exists=1 updated=1` and `railOf(txid)` returns `"card"`.

## 6. Application — zero domain edits, disclosed application changes

| File | Rationale |
|---|---|
| `CreatePaymentUseCase.java` | Catch clause: `PspDeclinedException` (402) → `runDeclined` + rethrow (deduped idempotency, FAILED + payment.failed). Existing `RuntimeException` catch now only covers non-decline PSP errors (502 path unchanged). No change to runCore, runSuccess, or runExhaustion logic. |
| `ReconciliationUseCase.java` | Unchanged from S0 (already resolves rail via `railOf` before PSP poll). |

## 7. Test changes

**New test files:**

| File | Purpose |
|---|---|
| `apps/psp-simulator/.../CardChargesControllerTest.java` | 4 tests: approve born PAID (1 dispatch), magic-amount decline 402 + 0 dispatch, GET PAID, missing token 400. Uses `@Import(CardTestConfig)` with `@Primary RecordingWebhookDispatcher`. |
| `modules/payments/.../CardChargeAdapterTest.java` | 4 tests: 201 → PAID ChargeResult, 402 → PspDeclinedException, 409 → read-back, getCob PAID. Real JDK HttpClient against `com.github.tomakehurst:wiremock-jre8-standalone`. |
| `apps/api/.../CardPaymentIT.java` | 4 tests: happy approve + webhook confirm + single journal, 402 decline + idempotent reattempt, replay no double journal, reconciler confirms via GET. Full context: real PG + Flyway, real HMAC webhook, static `HttpServer` stub (`System.setProperty("dargent.psp.base-url")`), fixed Clock. |

**Modified test files:**

| File | Change |
|---|---|
| `CreatePaymentUseCaseTest.java` | `rails` map changed from single `PspPort` to `Map.of("pix", pixRail, "card", cardRailMock)`; `Input(...)` adds `method`+`cardToken` params; new tests: `card_decline_marks_failed_*` + `card_create_returns_pending_with_null_presentment`; `cardRail` is a mock (`new PixRail(mock, null, null, null)`). |
| `ReconciliationUseCaseTest.java` | `FakePspPort` changed to implement `PaymentRail`; rails map keyed by `rail()` = `"pix"` (same 6 spec rows, no behavioral change). |

## 8. Smoke script (scripts/smoke.sh)

Legs 5–7 added for card rail:
- **Leg 5 (card create):** `POST /v1/payments` with `method:"card"` → 201 PENDING + `brcode:null` + `X-Request-Id`.
- **Leg 6 (card confirm poll):** deadline-poll GET → CONFIRMED (webhook fires during the create PSP call — no explicit pay step).
- **Leg 7 (card GET detail):** amount echoed + explicit-null `brcode` retained.

> **Smoke leg 7 correction (M5 B1 hotfix):** the first shipped leg 7 asserted `fee`, which the
> GET contract never emits (design §6.2 has no `fee` — E12 deviation). That single assertion
> reddened runtime-smoke twice (PR CI + main push #286). Corrected to assert `amount` + `brcode:null`.

**Masking extension of FINDING-S1-3 (post-hotfix re-check):** the rail-column no-op masked
**two further runtime behaviors**, all from the same root cause (now fixed by the flush):
1. **Controller presentment** (`PaymentController.presentmentFor`) — a card GET would have
   presented a PIX BR Code, because `railOf` defaulted to `"pix"`.
2. **Reconciler routing** (`ReconciliationUseCase.reconcileOne`) — card rows were polled via
   the PIX cob endpoint (404 → ladder advance → card never reconciled).

Both are pinned by green tests: `CardPaymentIT.card_reconciler_*` (1) and smoke leg 7 `brcode:null`
on a confirmed card GET (2). No additional code change was required — the flush fix removed all three.

## 9. Test taxonomy alignment

| Scenario (testing-playbook §5) | Implementation |
|---|---|
| Card create → PSP 201 PAID → PENDING + rail=card + brcode null | `CardPaymentIT.card_approve_*` + `CardChargeAdapterTest` + `CardChargesControllerTest` |
| Card PSP 402 → FAILED + payment.failed + idempotency delete | `CardPaymentIT.card_decline_*` + `CreatePaymentUseCaseTest.card_decline_*` |
| Card webhook confirm → CONFIRMED + one journal | `CardPaymentIT.card_approve_*` (real HMAC) |
| Card reconciler confirm → CONFIRMED via GET | `CardPaymentIT.card_reconciler_*` |
| Card replay → same body + 0 new rows | `CardPaymentIT.card_replay_*` |

## 10. Proof evidence (run at HEAD of PR #B)

```
./mvnw verify                     → BUILD SUCCESS
scripts/check-boundaries.sh       → OK
scripts/check-coverage.sh         → all floors met
scripts/smoke.sh $API $KEY $PSP   → SMOKE PASS (legs 1-7)
```
