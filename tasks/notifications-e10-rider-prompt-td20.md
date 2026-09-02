# Rider Prompt — E10 TD-20: cross-tenant proof + naming guard + merchantId restored

**Issued:** 2026-09-02 · **Executor:** the AI Software Engineer · **Auditor:** the governance side.
**Basis:** owner decision on the closure audit (TD-20: FULL rider). RAT-E10-IT is sealed
owner-approved — the IT harness altitude stays as-built; do NOT touch it. **Push is the owner's action.**

## Context (audited facts)

- main = `be91286`, run **#120 `33684616551`** green. M2 flipped at `fa00eb3` (#119) with citation
  `be91286` (#120). This rider is POST-FLIP content: it re-opens the pairing chain — your rider
  content commit becomes the new last-content commit and needs its own green run + citation commit.
- S6 is real and principal-scoped (`NotificationController` → `queryPort.findPage(principal.merchantId(), …)`).
- Adjudications being enforced (workspace spec §7/§8.3, TD-17/TD-18 as ruled):
  1. `merchant_id` is an OUTPUT echo — it must be IN each item (TD-17 ruling: input-only ban).
  2. Cross-tenant isolation proof must exist as a test (TD-17 §8.3).
  3. Naming guard must include the negatives (TD-18 §8.3).

## The rider — ONE commit: `feat(notifications): tenant echo and isolation proof on read API (E10 rider)`

1. **Restore `merchantId` in the response**: `NotificationView` gains `merchantId`;
   `JdbcNotificationQuery` selects `merchant_id` (payload stays excluded); shape assertions updated.
   The response item shape becomes exactly: `id, eventId, type, txid, merchantId, occurredAt, createdAt`.
2. **Cross-tenant isolation test** (`NotificationsApiIT`): seed rows for a SECOND merchant
   (own API key — reuse the provisioning pattern from the payments ITs); assert the principal's list
   NEVER returns the other merchant's rows and the other merchant's list never returns the principal's
   (both directions, one test or two — your call, name it like the house:
   `cross_tenant_rows_are_never_visible_across_credentials` or similar).
3. **Naming guard (negatives)**: in the shape test, assert the item does NOT contain the keys
   `event_id`, `occurred_at`, `created_at`, `next_cursor` (positives already asserted).
4. **Matrix** (`tasks/notifications-e10-spec.md` §10): amend the S6 row — add this commit's pair and
   note the TD-20 rider; keep the flip history intact (do not rewrite settled rows).

## Discipline

- Zero touches outside `NotificationView`/`JdbcNotificationQuery`/`NotificationsApiIT`/spec matrix.
  No main-config changes, no consumer changes, no ledger/payments/psp files.
- Commit message = diff; every red cited with id + written explanation; run pairs number AND id.
- Stop-and-report on anything that pulls wider.

## Handoff

Rider commit sha + message; green run (number AND id); citation commit sha recording the pair;
exact head sha. Then stop. On verified audit: **TD-20 closes, and E10/M2 are declared closed by the
governance channel** (the epics.md flip itself rides the owner's governance commit — not yours).
