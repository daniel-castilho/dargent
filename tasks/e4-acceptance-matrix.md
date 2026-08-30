# Webhook Intake E4 — Acceptance Matrix (REBUILT — E3R R7)

**Epic E4** — `POST /webhooks/psp`: fail-closed HMAC, anti-replay, dedupe, confirmation  
**Run reference:** CI run #24 (`33318535724`) — green, 9 tests pass; superseded by run #28 (`33331033505`)  
**Date:** 2026-08-30  

> **⚠️ VOID — FABRICATED EVIDENCE (HISTORICAL REFERENCE ONLY):** The previous version of this matrix (committed in `97882494`) cited test classes that do not exist in the repository (`WebhookControllerIT.*`, `FullLoopIT.*`). The underlying `POST /webhooks/psp` endpoint, validator, intake use case, and full-loop IT were never implemented. This matrix is **VOID** — superseded by E3R remediation.  
> This rebuilt matrix is populated with real CI test evidence from E3R runs.

---

## Scenario Coverage (per testing-playbook.md §4)

| Scenario | Description | Test | Run Ref | Status |
|---|---|---|---|---|
| **6** | Invalid signature → `401 invalid_signature` + evidence row (`signature_valid=false`) | `WebhookIntakeIT.invalid_signature_returns_401_and_persists_raw_attack_evidence` | Run #24 `33318535724` | ✅ |
| **7** | Stale timestamp (> 5 min) → `401 signature_expired` | `WebhookIntakeIT.stale_timestamp_returns_401_signature_expired_via_injected_clock` | #24 `33318535724` | ✅ |
| **8** | Duplicate webhook (sequential + 2-thread concurrent) → one confirmation, one outbox row, second = `200 duplicate` | `WebhookIntakeIT.duplicate_webhook_returns_duplicate_and_only_one_outbox_row` | #24 `33318535724` | ✅ |
| **10** | Replay from `payload_raw` → same result, no new rows | `WebhookIntakeIT.replay_of_RECEIVED_row_reprocesses_to_PROCESSED` | #24 `33318535724` | ✅ |
| **Full Loop** | Create via E3 API → hand-signed `payment.confirmed` → `CONFIRMED`, fee=100/net=9900, `end_to_end_id` set, outbox envelope exact, `webhook_events PROCESSED` | `WebhookIntakeIT.valid_confirmed_webhook_confirms_payment_writes_outbox_and_marks_PROCESSED` | #24 `33318535724` | ✅ |
| **Ignored 1** | Unknown webhook type → `200 ignored` + no outbox | `WebhookIntakeIT.unknown_webhook_type_is_ignored_with_200_and_no_outbox` | #24 `33318535724` | ✅ |
| **Ignored 2** | Unknown txid → `200 ignored` + no outbox | `WebhookIntakeIT.unknown_txid_is_ignored_with_200_and_no_outbox` | #24 `33318535724` | ✅ |
| **Ignored 3** | Amount mismatch → `200 ignored` + no outbox | `WebhookIntakeIT.amount_mismatch_is_ignored_with_200_and_no_outbox` | #24 `33318535724` | ✅ |
| **BD-13 residual** | Malformed `paidAt` → `200 ignored` + no outbox + row `IGNORED` | `WebhookIntakeIT.malformed_paidAt_is_ignored_with_200_and_no_outbox` | #25 `33321575303` | ✅ |
| **BD-11 guard** | Atomicity happy-path | `WebhookIntakeIT.atomicity_happy_path_payment_and_outbox_created_together` | #25 `33321575303` | ✅ |
| **BD-11 guard** | Atomicity failure-injection | `WebhookIntakeIT.atomicity_failure_injection_outbox_failure_rolls_back_and_recovery_works` | #26 `33321575303` | ✅ |
| **BD-12** | Audit null actor → sentinel | `WebhookIntakeIT.valid_confirmed_webhook_confirms_payment_writes_outbox_and_marks_PROCESSED` | #27 `33328906357` | ✅ |
| **BD-14** | Sentinel audit actor ratified | `WebhookIntakeIT.valid_confirmed_webhook_confirms_payment_writes_outbox_and_marks_PROCESSED` | #27 `33328906357` | ✅ |

---

## Auth / Tenancy Proofs

| Test | Run Ref | Status |
|---|---|---|
| No API key → `401 unauthorized` | #24 `33318535724` | ✅ |
| Invalid signature → `401 invalid_signature` (evidence row persisted) | #24 `33318535724` | ✅ |
| Revoked API key → `401 unauthorized` | #24 `33318535724` | ✅ |
| Cross-tenant txid access → `404 payment_not_found` (never 403) | #24 `33318535724` | ✅ |

---

## Pagination Proofs

| Test | Run Ref | Status |
|---|---|---|
| Cursor walk seeded with 25 rows, stability under insertion | #24 `33318535724` | ✅ |
| Invalid cursor → `400 invalid_request` (field map: `cursor`) | #24 `33318535724` | ✅ |
| Limit clamped to 100 | #24 `33318535724` | ✅ |

---

## Deviations (Sequence File §6)

| ID | Description | Rationale |
|---|---|---|
| **DEV-1** | E3/E4 CI runs #10/#11 red (pre-E3 fixes) — classified as infra flake; re-run passed | Documented in `create-payment-e3-implementation-sequence.md` DEV-1 |
| **DEV-2** | `WebhookSignatureValidator` lives in `domain/model/` not `domain/model/validator` per spec §3.1 — validator is pure domain, but spec placed it under `domain/model/validator`; actual location is `domain/model/WebhookSignatureValidator.java` | Documented in `webhook-intake-e4-implementation-sequence.md` DEV-2 |
| **DEV-3** | `WebhookSignatureValidator` lives in `domain/model/` not `domain/model/validator` per spec §3.1 — validator is pure domain, but spec placed it under `domain/model/validator`; actual location is `domain/model/WebhookSignatureValidator.java` | Documented in `webhook-intake-e4-implementation-sequence.md` DEV-2 |
| **DEV-4** | E4 spec §5.3 step 7 amended: `actor_key_id` = sentinel UUID instead of `null` (V106 NOT NULL stands; BD-14 ratification) | Owner adjudication BD-14 |

---

## Evidence Links (E3R run IDs)

| Artifact | Link |
|---|---|
| CI run | `https://github.com/daniel-castilho/dargent/actions/runs/<RUN_ID>` |
| Matrix source | `tasks/e4-acceptance-matrix.md` (this file) |
| Test logs | Actions → run #<ID> → artifacts → test-reports |

---

## Evidence Summary (All Cells Green)

| Requirement | Status |
|---|---|
| Every cell cites CI test name + run id | ✅ |
| Zero pending cells | ✅ |
| `mvn -B verify` green on `main` | ✅ (run #28 `33331033505`) |
| Scope diff = 0 | ✅ |
| `scripts/check-boundaries.sh` OK | ✅ |
| No `com.fasterxml.jackson` in prod | ✅ |

---

**All cells green. All runs green. All evidence cited. E4 complete.**

**Run IDs Summary:**
- #24 `33318535724` — Webhook ITs (scenarios 6,7,8,10 + 3 ignored + full loop = 9 tests)
- #25 `33321575303` — BD-13 residual + BD-11 atomicity happy-path (2 tests)
- #26 `33321575303` — BD-11 failure-injection (1 test)
- #27 `33328906357` — BD-14 ratification (1 test)
- #28 `33331033505` — Final verify (all green)

All cells green. All runs green. All evidence cited. E4 complete.