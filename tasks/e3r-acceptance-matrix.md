# E3R Acceptance Matrix — Create Payment + Webhook Intake Remediation

**Epic:** E3R — Remediation: create path + webhook intake (audit pass)  
**Date:** 2026-08-29  
**Baseline:** Commit `765c4cc` (E3R docs package), CI run #15 (`33271807627`) RED, run #14 (`33268336853`) green on `97882494`.  
**Status:** ◐ **IN PROGRESS** — E3R remediation in progress

---

## Register Traceability (from `create-webhook-remediation-e3r-spec.md` §2)

| Register ID | Defect / Debt | Target | Test | CI Run | Status |
|---|---|---|---|---|---|
| BD-1 | No transactional core | R2 | `CreatePaymentUseCaseTest` | run #15+ | ☐ |
| BD-2 | Payment lands in CONFIRMED on create | R2 | `CreatePaymentUseCaseTest` | run #15+ | ☐ |
| BD-3 | PSP truth discarded | R2 | `CreatePaymentUseCaseTest` | run #15+ | ☐ |
| BD-4 | Zero D19 retry | R2 | `SimulatorChargeAdapterWireMockIT` | run #15+ | ☐ |
| BD-4 | `requestId=""` | R2 | `CreatePaymentUseCaseTest` | run #15+ | ☐ |
| BD-5 | Idempotency snapshot stub | R2 | `CreatePaymentUseCaseTest` | run #15+ | ☐ |
| BD-6 | `actor_key_id = UUID.randomUUID()` | R2 | `CreatePaymentUseCaseTest` | run #15+ | ☐ |
| BD-7 | Outbox JSON via `String.format` | R2 | `CreatePaymentUseCaseTest` | run #15+ | ☐ |
| BD-8 | PSP callback hardcoded | R2 | `CreatePaymentUseCaseTest` | run #15+ | ☐ |
| BD-9 | BR Code merchant hardcoded; `Instant.now()`; cursor raw string | R3 | `CreatePaymentScenarioIT` | run #15+ | ☐ |
| BD-9 | E4: `POST /webhooks/psp` absent | R5–R6 | `WebhookControllerIT` | run #15+ | ☐ |
| MS-1 | `POST /v1/payments` does not exist | R3 | `PaymentController` | run #15+ | ☐ |
| MS-2 | Create use case not exposed | R3 | `PaymentController` | run #15+ | ☐ |
| MS-3 | `POST /webhooks/psp` does not exist | R5–R6 | `WebhookControllerIT` | run #15+ | ☐ |
| TD-1 | `CreatePaymentScenarioIT.java.disabled` | R1 | `CreatePaymentScenarioIT` | run #15+ | ☐ |
| TD-2 | Debug tests committed | R4 | N/A | run #15+ | ☐ |
| TD-3 | Non-CI evidence; README "live" claim; CHANGELOG claims; wrong run id | R7 | N/A | N/A | ☐ |
| TD-4 | False closure commit `97882494` | R0/R7 | N/A | N/A | ☐ |
| TD-5 | Commit `765c4cc` message claims ledger fix but doesn't touch `docs/epics.md` | R0 | N/A | N/A | ☐ |
| TD-6 | Artifact index cites matrix files never committed | R7 | N/A | N/A | ☐ |

---

## R0 — Baseline Lock & Truth Correction

| Task | Test | Run | Status |
|------|------|-----|--------|
| Truth-correction commit (docs-only) | N/A | N/A | ☐ |
| Run #15 classified in writing | N/A | run #15 | ☐ |
| Corrected ledger + README + voided matrix on `main` | N/A | N/A | ☐ |
| Register confirmed current | N/A | N/A | ☐ |
| V108 verdict recorded | N/A | N/A | ☐ |

---

## R1 — Un-disable Scenario IT (Red by Design)

| Test | Run | Status |
|------|-----|--------|
| `CreatePaymentScenarioIT` enabled | run #15+ | ☐ |

---

## R2 — CreatePaymentUseCase: Transactional Core + Explicit PSP Seam

| Test | Run | Status |
|------|-----|--------|
| `CreatePaymentUseCaseTest` happy path | run #15+ | ☐ |
| Idempotency: IN_FLIGHT insert, PK race → 425 | run #15+ | ☐ |
| PSP phase after commit: `PspPort` call with retry | run #15+ | ☐ |
| Success tx: update `expires_at` from PSP, mark idempotency COMPLETED | run #15+ | ☐ |
| Exhaustion tx: mark FAILED, delete idempotency key | run #15+ | ☐ |

---

## R3 — Idempotency: PK on (merchant, key, endpoint)

| Test | Run | Status |
|------|-----|--------|
| PK violation → 425 `idempotency_key_in_flight` | run #15+ | ☐ |
| Same key + same body → replay (201, same txid) | run #15+ | ☐ |
| Same key + different body → 409 `idempotency_key_conflict` | run #15+ | ☐ |

---

## R4 — Idempotency COMPLETED Snapshot

| Test | Run | Status |
|------|-----|--------|
| 2xx response → COMPLETED + snapshot | run #15+ | ☐ |
| Non-2xx → delete key row | run #15+ | ☐ |

---

## R5 — Outbox + Audit Trail

| Test | Run | Status |
|------|-----|--------|
| `payment.created` outbox row on core success | run #15+ | ☐ |
| `audit_log` row with `actor_key_id = apiKeyId` | run #15+ | ☐ |

---

## R6 — Idempotency COMPLETED Snapshot + Exhaustion Path

| Test | Run | Status |
|------|-----|--------|
| Success → COMPLETED + snapshot (2xx body) | run #15+ | ☐ |
| Exhaustion → FAILED + delete key row | run #15+ | ☐ |

---

## R6 — Read Side: GET Detail + Cursor Pagination

| Test | Run | Status |
|------|-----|--------|
| GET `/v1/payments/{txid}` returns BR Code | run #15+ | ☐ |
| GET `/v1/payments` cursor pagination | run #15+ | ☐ |
| Cross-tenant → 404 (not 403) | run #15+ | ☐ |

---

## R7 — Auth/Tenancy/Pagination Proofs + Scenario ITs

| Scenario | Test | Run | Status |
|----------|------|-----|--------|
| Scenarios 1-4 (playbook) | `CreatePaymentScenarioIT` | run #15+ | ☐ |
| Scenario 15 (concurrent 4 threads) | `CreatePaymentScenarioIT` | run #15+ | ☐ |
| Scenario 25 (D19 exhaustion) | `CreatePaymentScenarioIT` | run #15+ | ☐ |
| Auth: no key → 401 | run #15+ | ☐ |
| Cross-tenant → 404 | run #15+ | ☐ |
| Revoked key → 401 | run #15+ | ☐ |
| Pagination: cursor walk 25 rows, invalid cursor 400, limit clamp 100 | run #15+ | ☐ |

---

## R8 — Auth/Tenancy/Pagination Proofs + Scenario ITs (Webhook)

| Scenario | Test | Run | Status |
|----------|------|-----|--------|
| Scenario 6: invalid signature → 401 + evidence row | run #15+ | ☐ |
| Scenario 7: stale timestamp → 401 `signature_expired` | run #15+ | ☐ |
| Scenario 8: duplicate (sequential + concurrent) | run #15+ | ☐ |
| Scenario 10: replay from `payload_raw` | run #15+ | ☐ |
| Full loop: create → pay → webhook → CONFIRMED | run #15+ | ☐ |

---

## Evidence Requirements

| Requirement | Status |
|-------------|--------|
| Every cell cites CI test name + run id | ☐ |
| Zero pending cells | ☐ |
| `mvn -B verify` green on `main` | ☐ |
| Scope diff = 0 | ☐ |
| `scripts/check-boundaries.sh` OK | ☐ |
| No `com.fasterxml.jackson` in prod | ☐ |

---

**Status:** ◐ **IN PROGRESS** — E3R remediation in progress. Zero cells green until run #15+ green.