# Webhook Intake E4 — Acceptance Matrix (REBUILT — E3R R7)

**Epic E4** — `POST /webhooks/psp`: fail-closed HMAC, anti-replay, dedupe, confirmation  
**Run reference:** CI run #13 (id 33267438415) — green, 113 tests pass (historical reference)  
**Future reference:** E3R run #15+ (TBD) — will supersede historical references  
**Date:** 2026-08-29  

> **⚠️ VOID — FABRICATED EVIDENCE (HISTORICAL REFERENCE ONLY):** The previous version of this matrix (committed in `97882494`) cited test classes that do not exist in the repository (`WebhookControllerIT.*`, `FullLoopIT.*`). The underlying `POST /webhooks/psp` endpoint, validator, intake use case, and full-loop IT were never implemented. This matrix is **VOID** — superseded by E3R remediation.  
> This rebuilt matrix will be populated with real CI test evidence from E3R runs.

---

## Scenario Coverage (per testing-playbook.md §4)

| Scenario | Description | Test | Run Ref | Status |
|---|---|---|---|---|
| **6** | Invalid signature → `401 invalid_signature` + evidence row (`signature_valid=false`) | `WebhookControllerIT.invalid_signature_rejected_with_evidence_row` | TBD | ☐ |
| **7** | Stale timestamp (> 5 min) → `401 signature_expired` | `WebhookControllerIT.stale_timestamp_rejected_with_signature_expired` | TBD | ☐ |
| **8** | Duplicate webhook (sequential + 2-thread concurrent) → one confirmation, one outbox row, second = `200 duplicate` | `WebhookControllerIT.duplicate_webhook_sequential_returns_200_duplicate` + `...concurrent_returns_200_duplicate` | TBD | ☐ |
| **10** | Replay from `payload_raw` → same result, no new rows | `WebhookControllerIT.replay_from_payload_raw_same_result` | TBD | ☐ |
| **Full Loop** | Create via E3 API → hand-signed `payment.confirmed` → `CONFIRMED`, fee=100/net=9900, `end_to_end_id` set, outbox envelope exact, `webhook_events PROCESSED` | `FullLoopIT.create_pay_then_webhook_confirms` | TBD | ☐ |

---

## Auth / Tenancy Proofs

| Test | Run Ref | Status |
|------|---------|--------|
| No API key → `401 unauthorized` | TBD | ☐ |
| Invalid signature → `401 invalid_signature` (evidence row persisted) | TBD | ☐ |
| Revoked API key → `401 unauthorized` | TBD | ☐ |
| Cross-tenant txid access → `404 payment_not_found` (never 403) | TBD | ☐ |

---

## Pagination Proofs

| Test | Run Ref | Status |
|------|---------|--------|
| Cursor walk seeded with 25 rows, stability under insertion | TBD | ☐ |
| Invalid cursor → `400 invalid_request` (field map: `cursor`) | TBD | ☐ |
| Limit clamped to 100 | TBD | ☐ |

---

## Deviations (Sequence File §6)

| ID | Description | Rationale |
|---|---|---|
| **DEV-1** | E3/E4 CI runs #10/#11 red (pre-E3 fixes) — classified as infra flake; re-run passed | Documented in `create-payment-e3-implementation-sequence.md` DEV-1 |
| **DEV-2** | `WebhookSignatureValidator` lives in `domain/model/` not `domain/model/validator` per spec §3.1 — validator is pure domain, but spec placed it under `domain/model/validator`; actual location is `domain/model/WebhookSignatureValidator.java` | Documented in `webhook-intake-e4-implementation-sequence.md` DEV-2 |

---

## Evidence Links (to be populated with E3R run IDs)

| Artifact | Link |
|---|---|
| CI run | `https://github.com/daniel-castilho/dargent/actions/runs/<RUN_ID>` |
| Matrix source | `tasks/e4-acceptance-matrix.md` (this file) |
| Test logs | Actions → run #<ID> → artifacts → test-reports |

---

**All cells pending. Zero cells green until E3R runs green.**

---

*Signed off: TBD — after E3R run #15+ green*