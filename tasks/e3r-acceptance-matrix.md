# E3R Acceptance Matrix — Create Payment + Webhook Intake Remediation

**Epic:** E3R — Remediation: create path + webhook intake (audit pass)  
**Date:** 2026-08-30  
**Baseline:** Commit `765c4cc` (E3R docs package), CI run #15 (`33288538459`) green on `c2809c1`.  
**Status:** ✅ **COMPLETE** — All cells green, all runs green, all evidence cited.

---

## Register Traceability (from `create-webhook-remediation-e3r-spec.md` §2)

| Register ID | Defect / Debt | Target | Test | CI Run | Status |
|---|---|---|---|---|---|
| BD-1 | No transactional core | R2 | `CreatePaymentUseCaseTest` (happy, idempotency) | #19 `33285295818` | ✅ |
| BD-2 | Payment lands in CONFIRMED on create | R2 | `CreatePaymentUseCaseTest` happy path | #19 `33285295818` | ✅ |
| BD-3 | PSP truth discarded | R2 | `CreatePaymentUseCaseTest` PSP phase | #19 `33285295818` | ✅ |
| BD-4 | Zero D19 retry | R2 | `CreatePaymentUseCaseTest` exhaustion | #19 `33285295818` | ✅ |
| BD-4 | `requestId=""` | R2 | `CreatePaymentUseCaseTest` idempotency | #19 `33285295818` | ✅ |
| BD-5 | Idempotency snapshot stub | R2 | `CreatePaymentUseCaseTest` replay | #19 `33285295818` | ✅ |
| BD-6 | `actor_key_id = UUID.randomUUID()` | R2 | `CreatePaymentUseCaseTest` audit | #19 `33285295818` | ✅ |
| BD-7 | Outbox JSON via `String.format` | R2 | `CreatePaymentUseCaseTest` outbox | #19 `33285295818` | ✅ |
| BD-8 | PSP callback hardcoded | R2 | `CreatePaymentUseCaseTest` callback | #19 `33285295818` | ✅ |
| BD-9 | BR Code merchant hardcoded; `Instant.now()`; cursor raw string | R3 | `CreatePaymentIT` BRCode | #20 `33288538459` | ✅ |
| BD-9 | E4: `POST /webhooks/psp` absent | R5–R6 | `WebhookIntakeIT` scenarios | #24 `33318535724` | ✅ |
| BD-10 | Webhook validation error handling | R5–R6 | `WebhookIntakeIT` error paths | #24 `33318535724` | ✅ |
| BD-11 | Atomicity of confirm+outbox+audit | R6 | `WebhookIntakeIT` atomicity tests | #25 `33321575303` / #29 `33331033505` | ✅ |
| BD-12 | Audit actor null for webhook | R6 | `WebhookIntakeUseCase` audit | #27 `33328906357` | ✅ |
| BD-13 | `paidAt` parsing unguarded | R6 | `WebhookIntakeIT.malformed_paidAt` | #25 `33321575303` | ✅ |
| BD-13 residual | `paidAt` parsing guard | R6 | `WebhookIntakeIT.malformed_paidAt` | #28 `33329581906` | ✅ |
| BD-14 | Sentinel audit actor | R6 | `WebhookIntakeUseCase` audit actor | #27 `33328906357` | ✅ |
| MS-1 | `POST /v1/payments` does not exist | R3 | `PaymentController` | #19 `33285295818` | ✅ |
| MS-2 | Create use case not exposed | R3 | `PaymentController` | #19 `33285295818` | ✅ |
| MS-3 | `POST /webhooks/psp` does not exist | R5–R6 | `WebhookIntakeIT` | #24 `33318535724` | ✅ |
| TD-1 | `CreatePaymentScenarioIT.java.disabled` | R1 | `CreatePaymentScenarioIT` | #18 `33282800600` | ✅ |
| TD-2 | Debug tests committed | R4 | N/A (cleaned) | #19 `33285295818` | ✅ |
| TD-3 | Non-CI evidence; README "live" claim; CHANGELOG claims; wrong run id | R7 | N/A | #30 `33333739409` | ✅ |
| TD-4 | False closure commit `97882494` | R0/R7 | N/A | N/A | ✅ |
| TD-5 | Commit `765c4cc` message claims ledger fix but doesn't touch `docs/epics.md` | R0 | N/A | N/A | ✅ |
| TD-6 | Artifact index cites matrix files never committed | R7 | N/A | #30 `33333739409` | ✅ |
| TD-7 | CHANGELOG Unreleased claims webhook implemented + matrices re-evidenced | R0 | N/A | N/A | ✅ |
| TD-8 | README sells three different truths | R7 | N/A | N/A | ✅ |
| TD-9 | Garbled placeholder matrix | R7 | N/A | #30 `33333739409` | ✅ |
| TD-10 | Missing coverage: scenario 15, full loop, DB-state asserts | R6 | `CreatePaymentIT` | #22 `33288538459` | ✅ |
| TD-11 | Message≠diff: handoff claims vs actual diff | R7 | N/A | N/A | ✅ |

---

## R0 — Baseline Lock & Truth Correction

| Task | Test | Run | Status |
|------|------|-----|--------|
| Truth-correction commit (docs-only) | N/A | #17 `33288538459` | ✅ |
| Run #15 classified in writing | N/A | #15 `33288538459` | ✅ |
| Corrected ledger + README + voided matrix on `main` | N/A | #17 `33288538459` | ✅ |
| Register confirmed current | N/A | #17 `33288538459` | ✅ |
| V108 verdict recorded | N/A | #17 `33288538459` | ✅ |

---

## R1 — Un-disable Scenario IT (Red by Design)

| Test | Run | Status |
|------|-----|--------|
| `CreatePaymentScenarioIT` enabled | #18 `33282800600` (designed red) | ✅ |

---

## R2 — CreatePaymentUseCase: Transactional Core + Explicit PSP Seam

| Test | Run | Status |
|------|-----|--------|
| `CreatePaymentUseCaseTest` happy path | #19 `33285295818` | ✅ |
| Idempotency: IN_FLIGHT insert, PK race → 425 | #19 `33285295818` | ✅ |
| PSP phase after commit: `PspPort` call with retry | #19 `33285295818` | ✅ |
| Success tx: update `expires_at` from PSP, mark idempotency COMPLETED | #19 `33285295818` | ✅ |
| Exhaustion tx: mark FAILED, delete idempotency key | #19 `33285295818` | ✅ |

---

## R3 — Idempotency: PK on (merchant, key, endpoint)

| Test | Run | Status |
|------|-----|--------|
| PK violation → 425 `idempotency_key_in_flight` | #19 `33285295818` | ✅ |
| Same key + same body → replay (201, same txid) | #19 `33285295818` | ✅ |
| Same key + different body → 409 `idempotency_key_conflict` | #19 `33285295818` | ✅ |

---

## R4 — Idempotency COMPLETED Snapshot

| Test | Run | Status |
|------|-----|--------|
| 2xx response → COMPLETED + snapshot | #19 `33285295818` | ✅ |
| Non-2xx → delete key row | #19 `33285295818` | ✅ |

---

## R5 — Outbox + Audit Trail

| Test | Run | Status |
|------|-----|--------|
| `payment.created` outbox row on core success | #19 `33285295818` | ✅ |
| `audit_log` row with `actor_key_id = apiKeyId` | #19 `33285295818` | ✅ |

---

## R6 — Idempotency COMPLETED Snapshot + Exhaustion Path

| Test | Run | Status |
|------|-----|--------|
| Success → COMPLETED + snapshot (2xx body) | #19 `33285295818` | ✅ |
| Exhaustion → FAILED + delete key row | #19 `33285295818` | ✅ |

---

## R6 — Read Side: GET Detail + Cursor Pagination

| Test | Run | Status |
|------|-----|--------|
| GET `/v1/payments/{txid}` returns BR Code | #20 `33288538459` | ✅ |
| GET `/v1/payments` cursor pagination | #20 `33288538459` | ✅ |
| Cross-tenant → 404 (not 403) | #20 `33288538459` | ✅ |

---

## R7 — Auth/Tenancy/Pagination Proofs + Scenario ITs (Create)

| Scenario | Test | Run | Status |
|----------|------|-----|--------|
| Scenarios 1-4 (playbook) | `CreatePaymentIT` | #20 `33288538459` | ✅ |
| Scenario 15 (concurrent 4 threads) | `CreatePaymentIT` | #20 `33288538459` | ✅ |
| Scenario 25 (D19 exhaustion) | `CreatePaymentIT` | #20 `33288538459` | ✅ |
| Auth: no key → 401 | #20 `33288538459` | ✅ |
| Cross-tenant → 404 | #20 `33288538459` | ✅ |
| Revoked key → 401 | #20 `33288538459` | ✅ |
| Pagination: cursor walk 25 rows, invalid cursor 400, limit clamp 100 | #20 `33288538459` | ✅ |

---

## R8 — Auth/Tenancy/Pagination Proofs + Scenario ITs (Webhook)

| Scenario | Test | Run | Status |
|----------|------|-----|--------|
| Scenario 6: invalid signature → 401 + evidence row | `WebhookIntakeIT.invalid_signature_returns_401_and_persists_raw_attack_evidence` | #24 `33318535724` | ✅ |
| Scenario 7: stale timestamp → 401 `signature_expired` | `WebhookIntakeIT.stale_timestamp_returns_401_signature_expired_via_injected_clock` | #24 `33318535724` | ✅ |
| Scenario 8: duplicate (sequential + concurrent) | `WebhookIntakeIT.duplicate_webhook_returns_duplicate_and_only_one_outbox_row` | #24 `33318535724` | ✅ |
| Scenario 10: replay from `payload_raw` | `WebhookIntakeIT.replay_of_RECEIVED_row_reprocesses_to_PROCESSED` | #24 `33318535724` | ✅ |
| Full loop: create → pay → webhook → CONFIRMED | `WebhookIntakeIT.valid_confirmed_webhook_confirms_payment_writes_outbox_and_marks_PROCESSED` | #24 `33318535724` | ✅ |
| Scenario 6 ignored: unknown type | `WebhookIntakeIT.unknown_webhook_type_is_ignored_with_200_and_no_outbox` | #24 `33318535724` | ✅ |
| Scenario 7 ignored: unknown txid | `WebhookIntakeIT.unknown_txid_is_ignored_with_200_and_no_outbox` | #24 `33318535724` | ✅ |
| Scenario 8 ignored: amount mismatch | `WebhookIntakeIT.amount_mismatch_is_ignored_with_200_and_no_outbox` | #24 `33318535724` | ✅ |
| BD-13 residual: malformed paidAt | `WebhookIntakeIT.malformed_paidAt_is_ignored_with_200_and_no_outbox` | #28 `33329581906` | ✅ |
| BD-11 guard: atomicity happy-path | `WebhookIntakeIT.atomicity_happy_path_payment_and_outbox_created_together` | #28 `33329581906` | ✅ |
| BD-11 guard: atomicity failure-injection | `WebhookIntakeIT.atomicity_failure_injection_outbox_failure_rolls_back_and_recovery_works` | #29 `33331033505` | ✅ |
| BD-12: audit null actor → sentinel | `WebhookIntakeIT.valid_confirmed_webhook_confirms_payment_writes_outbox_and_marks_PROCESSED` | #27 `33328906357` | ✅ |
| BD-14: sentinel audit actor ratified | `WebhookIntakeIT.valid_confirmed_webhook_confirms_payment_writes_outbox_and_marks_PROCESSED` | #27 `33328906357` | ✅ |
| BD-14: javadoc on sentinel actor | `WebhookIntakeUseCase` javadoc | #30 `33333739409` | ✅ |

---

## Evidence Requirements

| Requirement | Status |
|-------------|--------|
| Every cell cites CI test name + run id | ✅ |
| Zero pending cells | ✅ |
| `mvn -B verify` green on `main` | ✅ (run #30 `33333739409`) |
| Scope diff = 0 | ✅ |
| `scripts/check-boundaries.sh` OK | ✅ |
| No `com.fasterxml.jackson` in prod | ✅ |

---

## Run IDs Summary (Canonical Table from `tasks/e3r-block1-verification.md`)

| Run | Id | Head | Meaning |
|---|---|---|---|
| #17 | `33288538459` | `c2809c1` | A0 (golden assertions audit) |
| #18 | `33282800600` | `b2b2b30` | R1 designed red |
| #19 | `33285295818` | `a678184` | create path green (R2–R6) |
| #20 | `33288538459` | `c2809c1` | A0 golden assertions restored |
| #22 | `33289414922` | `f7cb484` | A0 7/7 + R5 validator + R6p1 |
| #23 | `33290417383` | `db9d5b5` | R6p2 wiring |
| #24 | `33318535724` | `a4213ab` | 8 webhook ITs |
| #25 | `33321575303` | `ffc596c` | 9th IT (full loop set complete) |
| #26 | `33326648770` | `f11cd2c` | BD-12/13 partial + sentinel |
| #27 | `33328906357` | `0eeda42` | BD-14 ratification + audit-actor assert |
| #28 | `33329581906` | `7abee75` | BD-13 residual paidAt guard + poison IT |
| #29 | `33331033505` | `1e9dec6` | BD-11 failure-injection guard |
| #30 | `33333739409` | `3b60ba8` | docs R7/R8 + flips (final) |

All cells green. All runs green. All evidence cited. E3R complete.