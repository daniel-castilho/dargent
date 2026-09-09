# M5 S0 diff audit — PaymentRail seam (PR #A)

Audit of the S0 extraction per the owner adjudication (2026-09-09): **D1 = cirúrgico-disclosed** —
`domain/` zero edits (hard); `application/` edits allowed, every file disclosed with a 1-line
rationale. Reference for the M5 flip citation `<diff-audit-ref> = docs/audit-m5-s0.md` at the commit
id this document ships in.

## 1. Domain — zero edits (hard)

`git diff --name-only HEAD -- modules/payments/src/main/java/io/dargent/payments/domain/` → **EMPTY**.
No existing domain file was modified. Two new interfaces were ADDED (additions are allowed):

| File | Type | Rationale |
|---|---|---|
| `domain/port/out/PaymentRail.java` | addition | The strategy seam the modules depend on instead of the PIX-named `PspPort`; extends it (inherits the shared charge/truth contract), adds `rail()` + `presentment()`. Pure interface, no Spring/JPA/AWS/Jackson. |
| `domain/port/out/RailAssignmentPort.java` | addition | Infrastructure-routing seam (which rail a payment rides) kept OUT of the aggregate on purpose — routing is infrastructure state, not domain state. |

## 2. Application — permitted, disclosed (rationale per file)

Exactly 2 files, both in `modules/payments/src/main/java/io/dargent/payments/application/`:

| File | 1-line rationale |
|---|---|
| `CreatePaymentUseCase.java` | Replaces the `PspPort` + `pixKey/receiverName/receiverCity` constructor inputs with the `PaymentRail` (charge call goes through the seam unchanged) + a `RailAssignmentPort` (persists `rail='pix'` inside the create transaction); `composeBrCode` (PIX presentment) moved to `PixRail.presentment()` — call sites unchanged in behaviour/bytes. |
| `ReconciliationUseCase.java` | Resolves the payment's rail before the PSP truth poll (`railOf(txid)` → `getCob` through the registered rail; PIX is the default for anything unregistered), so reconcileOne keeps its exact PIX semantics. |

No other application file changed.

## 3. Wiring (apps/api — not in the audit, disclosed for the abstraction review)

| File | Rationale |
|---|---|
| `config/PaymentsCompositionConfig.java` | Adds `pixRail` bean (wraps the existing `SimulatorChargeAdapter` with the PIX receiver profile — the @Primary test `PspPort` still flows in during ITs), `JdbcRailAssignmentPort` bean, and wires the new ctor inputs; the reconciler gets `Map.of("pix", pixRail)` + the rail-assignment port. |
| `controller/PaymentController.java` | Detail-view presentment now asks the rail (`rail.presentment(...)`) instead of composing `BrCode.of(...)` from controller-injected profile values — same bytes for PIX, rail-agnostic for card (S1). |

## 4. Payments module additions

| File | Purpose |
|---|---|
| `adapter/out/psp/PixRail.java` | PIX strategy: byte-for-byte delegation of charge/truth to the low-level adapter + EMV presentment via `BrCode.of`. |
| `adapter/out/persistence/JdbcRailAssignmentPort.java` | Owns the `payments.payments.rail` column (JPA entity deliberately does not map it); `assign` rides the create transaction. |
| `db/migration/payments/V113__payments_rail.sql` | Forward-only, expand-only: `ADD COLUMN rail VARCHAR(16) NOT NULL DEFAULT 'pix'` — backfills existing rows, blue/green compatible, card arrives in S1. |
| `test/.../PixRailTest.java` | New unit suite (delegation + presentment equality against the golden `BrCode`). |

## 5. Test changes

- `CreatePaymentUseCaseTest` — constructs the real `PixRail` over the mocked `PspPort` (same spy assertions); new test proves `railAssignment.assign(txid, "pix")` joins the core transaction.
- `ReconciliationUseCaseTest` — `FakePspPort` implements `PaymentRail`; in-memory `RailAssignmentPort` returns `"pix"`; all 6 spec rows unchanged.

## 6. Proof evidence (run at HEAD of PR #A)

- Full reactor `./mvnw verify` → BUILD SUCCESS 2026-09-09T11:58:40-04:00 (11:17 min; includes the entire apps/api Testcontainers suite: webhook intake/ladder, expiry, expired reconciler confirm/resurrect/give-up, refund races and balances, journal-coverage, admin ladders, scenario 20) and SpotBugs gate 0 bugs.
- Module unit suite: 153 tests green (incl. `PixRailTest` 4, `CreatePaymentUseCaseTest` 13, `ReconciliationUseCaseTest` 6, `BrCodeTest`, jqwik property rows, `PaymentsArchitectureTest`).
- Coverage floors: `scripts/check-coverage.sh` → all 5 floors PASS (payments 0.70 ✓).
- Runtime smoke: no env contract, no API contract, no route changed — smoke semantics unchanged (verified in CI).
- Spotless: green (applied; house one-normalize rule respected within this change, nothing else touched).

Pre-E17 WORKTREE scope confirmation: only the files above differ from `main`; no other file was reformatted by Spotless.