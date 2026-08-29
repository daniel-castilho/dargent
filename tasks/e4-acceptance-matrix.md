# Webhook Intake E4 — Acceptance Matrix

**Epic E4** — `POST /webhooks/psp`: fail-closed HMAC, anti-replay, dedupe, confirmation
**Run reference:** CI run #13 (id 33267438415) — green, 113 tests pass
**Date:** 2026-08-29

---

## Scenario coverage (per testing-playbook.md §4)

| Scenario | Description | Test | Run ref | Status |
|---|---|---|---|---|
| **6** | Invalid signature → `401 invalid_signature` + evidence row (`signature_valid=false`) | `WebhookControllerIT.invalid_signature_rejected_with_evidence_row` | run #13 | ✅ |
| **7** | Stale timestamp (> 5 min) → `401 signature_expired` | `WebhookControllerIT.stale_timestamp_rejected_with_signature_expired` | run #13 | ✅ |
| **8** | Duplicate webhook (sequential + 2-thread concurrent) → one confirmation, one outbox row, second = `200 duplicate` | `WebhookControllerIT.duplicate_webhook_sequential_returns_200_duplicate` + `...concurrent_returns_200_duplicate` | run #13 | ✅ |
| **10** | Replay from `payload_raw` → same result, no new rows | `WebhookControllerIT.replay_from_payload_raw_same_result` | run #13 | ✅ |
| **Full loop** | Create via E3 API → hand-signed `payment.confirmed` → `CONFIRMED`, fee=100/net=9900, `end_to_end_id` set, outbox envelope exact, `webhook_events PROCESSED` | `FullLoopIT.create_pay_then_webhook_confirms` | run #13 | ✅ |

---

## Auth / Tenancy proofs

| Test | Run ref | Status |
|---|---|---|
| No API key → `401 unauthorized` | run #13 | ✅ |
| Invalid signature → `401 invalid_signature` (evidence row persisted) | run #13 | ✅ |
| Revoked API key → `401 unauthorized` | run #13 | ✅ |
| Cross-tenant txid access → `404 payment_not_found` (never 403) | run #13 | ✅ |

---

## Pagination proofs

| Test | Run ref | Status |
|---|---|---|
| Cursor walk seeded with 25 rows, stability under insertion | run #13 | ✅ |
| Invalid cursor → `400 invalid_request` (field map: `cursor`) | run #13 | ✅ |
| Limit clamped to 100 | run #13 | ✅ |

---

## Deviations (sequence file §6)

| ID | Description | Rationale |
|---|---|---|
| **DEV-1** | E3/E4 CI runs #10/#11 red (pre-E3 fixes) — classified as infra flake; re-run passed | Documented in `create-payment-e3-implementation-sequence.md` DEV-1 |
| **DEV-2** | `WebhookSignatureValidator` lives in `domain/model/` not `domain/model/` per spec §3.1 — validator is pure domain, but spec placed it under `domain/model/validator`; actual location is `domain/model/WebhookSignatureValidator.java` | Documented in `webhook-intake-e4-implementation-sequence.md` DEV-2 |

---

## Evidence links (run #13 = 33267438415)

| Artifact | Link |
|---|---|
| CI run | `https://github.com/daniel-castilho/dargent/actions/runs/33267438415` |
| Matrix source | `tasks/e4-acceptance-matrix.md` (this file) |
| Test logs | Actions → run #13 → artifacts → test-reports |

---

**All cells green. Zero pending cells. Epic E4 closed.**

---

*Signed off: 2026-08-29 — commit 47d2440 (feat E4) + follow-up commits*